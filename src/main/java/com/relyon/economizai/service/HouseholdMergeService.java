package com.relyon.economizai.service;

import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.HouseholdScoped;
import com.relyon.economizai.model.enums.MergeCategory;
import com.relyon.economizai.repository.ConsumptionSnoozeRepository;
import com.relyon.economizai.repository.HouseholdCustomCategoryRepository;
import com.relyon.economizai.repository.HouseholdMarketAliasRepository;
import com.relyon.economizai.repository.HouseholdProductAliasRepository;
import com.relyon.economizai.repository.HouseholdProductCategoryOverrideRepository;
import com.relyon.economizai.repository.ManualBrandPreferenceRepository;
import com.relyon.economizai.repository.ManualPurchaseRepository;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.repository.ShoppingListRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Moves household-scoped data between households for merge (join) and restore (split).
 *
 * <p><b>Merge (join):</b> for each selected {@link MergeCategory}, move the joiner's
 * rows from their origin household into the target. Collision rule (host wins): if a
 * row's {@code collisionKey()} already exists in the target (the per-household UNIQUE
 * constraint would reject it), the TARGET's row is kept and the joiner's row is left
 * PARKED on its origin household (not moved, not deleted) so it can still be restored
 * on split. {@code originHousehold} is never rewritten — it's the provenance anchor.
 *
 * <p><b>Restore (split):</b> move back to a household every row whose
 * {@code originHousehold} is that household — i.e. each person reclaims exactly what
 * they brought, including parked-shadowed rows that never made it into the target.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HouseholdMergeService {

    private final ReceiptRepository receiptRepository;
    private final ShoppingListRepository shoppingListRepository;
    private final ManualPurchaseRepository manualPurchaseRepository;
    private final ConsumptionSnoozeRepository consumptionSnoozeRepository;
    private final HouseholdProductCategoryOverrideRepository categoryOverrideRepository;
    private final HouseholdCustomCategoryRepository customCategoryRepository;
    private final HouseholdProductAliasRepository productAliasRepository;
    private final HouseholdMarketAliasRepository marketAliasRepository;
    private final ManualBrandPreferenceRepository brandPreferenceRepository;

    /** Outcome counts for one category's merge, for logging + the API response. */
    public record MergeResult(int moved, int shadowed) {
        MergeResult plus(MergeResult o) { return new MergeResult(moved + o.moved, shadowed + o.shadowed); }
        static MergeResult zero() { return new MergeResult(0, 0); }
    }

    // Binds a MergeCategory to its repo's "find rows currently in household X" finder
    // and its save-all, both typed to the concrete HouseholdScoped entity. One record
    // per category lets the generic algorithm run over any entity uniformly.
    private record Binding<T extends HouseholdScoped>(
            Function<UUID, List<T>> findByHousehold,
            Function<UUID, List<T>> findByOriginHousehold,
            java.util.function.Consumer<List<T>> saveAll) {}

    @SuppressWarnings("unchecked")
    private <T extends HouseholdScoped> Binding<T> binding(MergeCategory category) {
        return (Binding<T>) switch (category) {
            case RECEIPTS -> bind(receiptRepository::findAllByHouseholdId, receiptRepository::findAllByOriginHouseholdId, receiptRepository::saveAll);
            case SHOPPING_LISTS -> bind(shoppingListRepository::findAllByHouseholdId, shoppingListRepository::findAllByOriginHouseholdId, shoppingListRepository::saveAll);
            case MANUAL_PURCHASES -> bind(manualPurchaseRepository::findAllByHouseholdId, manualPurchaseRepository::findAllByOriginHouseholdId, manualPurchaseRepository::saveAll);
            case CONSUMPTION_SNOOZES -> bind(consumptionSnoozeRepository::findAllByHouseholdId, consumptionSnoozeRepository::findAllByOriginHouseholdId, consumptionSnoozeRepository::saveAll);
            case CATEGORY_OVERRIDES -> bind(categoryOverrideRepository::findAllByHouseholdId, categoryOverrideRepository::findAllByOriginHouseholdId, categoryOverrideRepository::saveAll);
            case CUSTOM_CATEGORIES -> bind(customCategoryRepository::findAllByHouseholdId, customCategoryRepository::findAllByOriginHouseholdId, customCategoryRepository::saveAll);
            case PRODUCT_ALIASES -> bind(productAliasRepository::findAllByHouseholdId, productAliasRepository::findAllByOriginHouseholdId, productAliasRepository::saveAll);
            case MARKET_ALIASES -> bind(marketAliasRepository::findAllByHouseholdId, marketAliasRepository::findAllByOriginHouseholdId, marketAliasRepository::saveAll);
            case BRAND_PREFERENCES -> bind(brandPreferenceRepository::findAllByHouseholdId, brandPreferenceRepository::findAllByOriginHouseholdId, brandPreferenceRepository::saveAll);
        };
    }

    // Each repo exposes the same two finders + saveAll; wire them as method refs so the
    // binding is compile-checked (no reflection) and the generic algorithm is uniform.
    private <T extends HouseholdScoped> Binding<T> bind(Function<UUID, List<T>> byHousehold,
                                                        Function<UUID, List<T>> byOrigin,
                                                        Function<List<T>, ?> saveAll) {
        return new Binding<>(byHousehold, byOrigin, rows -> saveAll.apply(rows));
    }

    /**
     * Merge the joiner's selected categories from {@code origin} into {@code target}.
     * Returns the aggregate moved/shadowed counts. Runs inside the caller's transaction
     * (HouseholdService.join is @Transactional), so a failure rolls the whole join back.
     */
    public MergeResult merge(Household origin, Household target, Set<MergeCategory> categories) {
        var total = MergeResult.zero();
        for (var category : categories) {
            var result = mergeCategory(category, origin, target);
            total = total.plus(result);
            log.info("merge.category category={} moved={} shadowed={} origin={} target={}",
                    category, result.moved(), result.shadowed(), origin.getId(), target.getId());
        }
        return total;
    }

    private <T extends HouseholdScoped> MergeResult mergeCategory(MergeCategory category, Household origin, Household target) {
        Binding<T> b = binding(category);
        // Keys already present in the target — these collide (host wins).
        Set<String> targetKeys = collectKeys(b.findByHousehold().apply(target.getId()));
        // Within the joiner's own batch, the first occurrence of a key wins; later
        // dupes are shadowed too (can't insert two identical keys into the target).
        Set<String> claimed = new HashSet<>(targetKeys);

        var toMove = new java.util.ArrayList<T>();
        int shadowed = 0;
        for (T row : b.findByHousehold().apply(origin.getId())) {
            String key = row.collisionKey();
            if (key != null && claimed.contains(key)) {
                // Collision: keep target's, leave this one parked on its origin
                // (do NOT move it). Its originHousehold already points home, so split
                // can restore it later.
                shadowed++;
                continue;
            }
            if (key != null) {
                claimed.add(key);
            }
            row.setHousehold(target);   // origin_household_id stays untouched
            toMove.add(row);
        }
        if (!toMove.isEmpty()) {
            b.saveAll().accept(toMove);
        }
        return new MergeResult(toMove.size(), shadowed);
    }

    /**
     * Restore to {@code home} every row (across ALL categories) whose origin household
     * is {@code home} but currently lives elsewhere — used on split so each person
     * reclaims exactly what they brought, including rows that were shadowed (parked)
     * during the merge.
     */
    public MergeResult restoreOriginals(Household home) {
        var total = MergeResult.zero();
        for (var category : MergeCategory.values()) {
            total = total.plus(restoreCategory(category, home));
        }
        return total;
    }

    private <T extends HouseholdScoped> MergeResult restoreCategory(MergeCategory category, Household home) {
        Binding<T> b = binding(category);
        var toRestore = new java.util.ArrayList<T>();
        for (T row : b.findByOriginHousehold().apply(home.getId())) {
            if (!row.getHousehold().getId().equals(home.getId())) {
                row.setHousehold(home);   // bring it back home
                toRestore.add(row);
            }
        }
        if (!toRestore.isEmpty()) {
            b.saveAll().accept(toRestore);
            log.info("merge.restore category={} restored={} home={}", category, toRestore.size(), home.getId());
        }
        return new MergeResult(toRestore.size(), 0);
    }

    /**
     * Consent-approved copy: duplicate the grantor's own receipts currently in
     * {@code shared} into {@code destination}, as NEW rows owned by the same scanner
     * but homed in the destination. The grantor keeps their originals — this is a copy,
     * not a move. Returns the number of receipts copied. Skips any chave already in the
     * destination (the per-household UNIQUE guard). Receipt-only by design: the consent
     * scope is "the partner's purchase history", which is the receipts.
     */
    public int copyUserData(UUID grantorUserId, Household shared, Household destination) {
        var source = receiptRepository.findAllByUserIdAndHouseholdId(grantorUserId, shared.getId());
        if (source.isEmpty()) {
            return 0;
        }
        var existingKeys = collectKeys(receiptRepository.findAllByHouseholdId(destination.getId()));
        var copies = new java.util.ArrayList<com.relyon.economizai.model.Receipt>();
        for (var r : source) {
            if (existingKeys.contains(r.getChaveAcesso())) {
                continue;   // destination already has this NF-e — host wins
            }
            copies.add(copyReceiptHeader(r, destination));
        }
        if (!copies.isEmpty()) {
            receiptRepository.saveAll(copies);
            log.info("consent.copy grantor={} copied={} shared={} destination={}",
                    grantorUserId, copies.size(), shared.getId(), destination.getId());
        }
        return copies.size();
    }

    // Header-level copy of a receipt into a new household: a fresh row (new id via
    // @GeneratedValue) homed AND originating in the destination, preserving the scanner
    // and the receipt's identifying/financial fields. Items are NOT deep-copied here —
    // the consent scope is the partner's purchase HISTORY (markets, totals, dates), and
    // an item-level deep copy is out of scope for this first version (TODO(prod)).
    private com.relyon.economizai.model.Receipt copyReceiptHeader(com.relyon.economizai.model.Receipt src, Household destination) {
        return com.relyon.economizai.model.Receipt.builder()
                .user(src.getUser())
                .household(destination)
                .originHousehold(destination)
                .chaveAcesso(src.getChaveAcesso())
                .uf(src.getUf())
                .cnpjEmitente(src.getCnpjEmitente())
                .marketName(src.getMarketName())
                .marketAddress(src.getMarketAddress())
                .issuedAt(src.getIssuedAt())
                .totalAmount(src.getTotalAmount())
                .discountTotal(src.getDiscountTotal())
                .qrPayload(src.getQrPayload())
                .status(src.getStatus())
                .confirmedAt(src.getConfirmedAt())
                .build();
    }

    private <T extends HouseholdScoped> Set<String> collectKeys(List<T> rows) {
        var keys = new HashSet<String>();
        for (T row : rows) {
            var key = row.collisionKey();
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }
}
