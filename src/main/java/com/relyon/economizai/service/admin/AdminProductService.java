package com.relyon.economizai.service.admin;

import com.relyon.economizai.dto.request.MergeProductRequest;
import com.relyon.economizai.dto.request.SetProductBrandRequest;
import com.relyon.economizai.dto.response.DuplicateProductGroupResponse;
import com.relyon.economizai.dto.response.MissingBrandProductResponse;
import com.relyon.economizai.dto.response.ProductMergeResultResponse;
import com.relyon.economizai.dto.response.ProductResponse;
import com.relyon.economizai.dto.response.RecategorizeReportResponse;
import com.relyon.economizai.dto.response.RecategorizeResultResponse;
import com.relyon.economizai.exception.InvalidProductMergeException;
import com.relyon.economizai.exception.ProductNotFoundException;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.ProductAlias;
import com.relyon.economizai.model.enums.CategorizationSource;
import com.relyon.economizai.service.extraction.ProductExtractor;
import com.relyon.economizai.repository.ConsumptionSnoozeRepository;
import com.relyon.economizai.repository.HouseholdProductAliasRepository;
import com.relyon.economizai.repository.ManualPurchaseRepository;
import com.relyon.economizai.repository.PriceObservationRepository;
import com.relyon.economizai.repository.ProductAliasRepository;
import com.relyon.economizai.repository.ProductRepository;
import com.relyon.economizai.repository.ReceiptItemRepository;
import com.relyon.economizai.repository.ShoppingListItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin curation tools for the product catalog. Currently focused on
 * filling in missing brands (Item C.2) — surfacing products whose brand
 * extraction yielded null, with sample raw descriptions to give the
 * curator context.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminProductService {

    private static final int SAMPLE_DESCRIPTION_LIMIT = 5;

    private final ProductRepository productRepository;
    private final ProductAliasRepository aliasRepository;
    private final ReceiptItemRepository receiptItemRepository;
    private final PriceObservationRepository priceObservationRepository;
    private final ManualPurchaseRepository manualPurchaseRepository;
    private final ShoppingListItemRepository shoppingListItemRepository;
    private final HouseholdProductAliasRepository householdProductAliasRepository;
    private final ConsumptionSnoozeRepository consumptionSnoozeRepository;
    private final ProductExtractor productExtractor;

    @Transactional(readOnly = true)
    public Page<MissingBrandProductResponse> listMissingBrand(Pageable pageable) {
        var page = productRepository.findMissingBrand(pageable);
        if (page.isEmpty()) return page.map(p -> MissingBrandProductResponse.from(p, List.of()));
        var productIds = page.getContent().stream().map(p -> p.getId()).toList();
        var aliasesByProduct = aliasRepository.findByProductIdIn(productIds).stream()
                .collect(Collectors.groupingBy(a -> a.getProduct().getId()));
        return page.map(p -> MissingBrandProductResponse.from(p,
                aliasesByProduct.getOrDefault(p.getId(), List.of()).stream()
                        .limit(SAMPLE_DESCRIPTION_LIMIT)
                        .map(ProductAlias::getRawDescription)
                        .toList()));
    }

    /**
     * Dry-run: re-run the extraction cascade over every product's stored
     * description and report where the freshly-computed category differs from
     * what's stored. Same extractor ingestion uses, so this previews exactly
     * what {@link #recategorizeApply()} would change. Read-only.
     */
    @Transactional(readOnly = true)
    public RecategorizeReportResponse recategorizeReport() {
        var products = productRepository.findAll();
        var mismatches = new ArrayList<RecategorizeReportResponse.Row>();
        var applicable = 0;
        var skippedUser = 0;
        for (var product : products) {
            var suggested = productExtractor.extract(product.getNormalizedName());
            var suggestedCategory = suggested.category();
            if (suggestedCategory == null || suggestedCategory == product.getCategory()) continue;
            var userOverride = product.getCategorizationSource() == CategorizationSource.USER;
            if (userOverride) skippedUser++; else applicable++;
            mismatches.add(new RecategorizeReportResponse.Row(
                    product.getId(), product.getNormalizedName(), product.getEan(),
                    product.getCategory(), product.getCategorizationSource(),
                    suggestedCategory, suggested.categorizationSource(),
                    userOverride));
        }
        log.info("admin.product.recategorize.report total={} mismatches={} applicable={} skippedUser={}",
                products.size(), mismatches.size(), applicable, skippedUser);
        return new RecategorizeReportResponse(products.size(), mismatches.size(), applicable, skippedUser, mismatches);
    }

    /**
     * Apply re-categorization: for every product whose freshly extracted
     * category differs from the stored one, update it — UNLESS the category was
     * set manually (source=USER, never clobbered) or the extractor has no
     * suggestion (null category, never downgraded). Updates category +
     * categorizationSource to reflect the layer that decided.
     */
    @Transactional
    public RecategorizeResultResponse recategorizeApply() {
        var products = productRepository.findAll();
        var updated = 0;
        var skippedUser = 0;
        var unchanged = 0;
        for (var product : products) {
            var suggested = productExtractor.extract(product.getNormalizedName());
            var suggestedCategory = suggested.category();
            if (suggestedCategory == null || suggestedCategory == product.getCategory()) {
                unchanged++;
                continue;
            }
            if (product.getCategorizationSource() == CategorizationSource.USER) {
                skippedUser++;
                continue;
            }
            product.setCategory(suggestedCategory);
            product.setCategorizationSource(suggested.categorizationSource());
            updated++;
        }
        log.info("admin.product.recategorize.applied total={} updated={} skippedUser={} unchanged={}",
                products.size(), updated, skippedUser, unchanged);
        return new RecategorizeResultResponse(products.size(), updated, skippedUser, unchanged);
    }

    @Transactional
    public ProductResponse setBrand(UUID productId, SetProductBrandRequest request) {
        var product = productRepository.findById(productId)
                .orElseThrow(ProductNotFoundException::new);
        var brand = request.brand().trim();
        product.setBrand(brand);
        var saved = productRepository.save(product);
        log.info("admin.product.brand_set product={} brand='{}'", productId, brand);
        return ProductResponse.from(saved);
    }

    /**
     * Returns groups of products that share an exact `(genericName, brand,
     * packSize, packUnit)` profile — likely duplicates the curator may want
     * to merge. Only products with all four metadata dimensions populated
     * are eligible (others can't be reliably grouped). Order within a group
     * is by creation time, so the oldest product (the one that probably
     * has the most history) appears first — a natural default survivor.
     */
    @Transactional(readOnly = true)
    public List<DuplicateProductGroupResponse> listDuplicateGroups() {
        var products = productRepository.findDuplicateCandidates();
        var groups = new LinkedHashMap<String, List<Product>>();
        for (var p : products) {
            var key = p.getGenericName() + " " + p.getBrand() + " "
                    + p.getPackSize() + " " + p.getPackUnit();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
        var result = new ArrayList<DuplicateProductGroupResponse>(groups.size());
        for (var entry : groups.values()) {
            var head = entry.get(0);
            result.add(new DuplicateProductGroupResponse(
                    head.getGenericName(), head.getBrand(), head.getPackSize(), head.getPackUnit(),
                    head.getCategory(),
                    entry.stream().map(ProductResponse::from).toList()));
        }
        return result;
    }

    /**
     * Merges {@code absorbed} into {@code survivor}: aliases, receipt items,
     * price observations, manual purchases and shopping-list items are
     * repointed; household-scoped tables (household aliases, consumption
     * snoozes) drop absorbed's row when the survivor already has one for
     * that household — otherwise UNIQUE (household_id, product_id) would
     * fail.
     *
     * <p>Runs entirely inside one transaction. The {@code dryRun} flag
     * computes the migration counts without applying — useful for the
     * curator to sanity-check before committing. NO undo: once applied the
     * absorbed row is gone, so the dry run is the only safety net.
     */
    @Transactional
    public ProductMergeResultResponse merge(UUID survivorId, MergeProductRequest request) {
        var dryRun = Boolean.TRUE.equals(request.dryRun());
        if (Objects.equals(survivorId, request.absorbedId())) {
            throw new InvalidProductMergeException("survivor_equals_absorbed");
        }
        var survivor = productRepository.findById(survivorId)
                .orElseThrow(ProductNotFoundException::new);
        var absorbed = productRepository.findById(request.absorbedId())
                .orElseThrow(ProductNotFoundException::new);

        // Pre-count what would migrate (also serves as the dry-run output).
        var aliasCount = aliasRepository.countByProduct(absorbed);
        var receiptItemCount = receiptItemRepository.countByProduct(absorbed);
        var observationCount = priceObservationRepository.countByProduct(absorbed);
        var manualPurchaseCount = manualPurchaseRepository.countByProduct(absorbed);
        var shoppingItemCount = shoppingListItemRepository.countByProduct(absorbed);
        var householdAliasCount = householdProductAliasRepository.countByProduct(absorbed);
        var snoozeCount = consumptionSnoozeRepository.countByProduct(absorbed);

        if (dryRun) {
            log.info("admin.product.merge.dryrun survivor={} absorbed={} aliases={} receiptItems={} observations={} manualPurchases={} shoppingItems={} householdAliases={} snoozes={}",
                    survivorId, request.absorbedId(),
                    aliasCount, receiptItemCount, observationCount, manualPurchaseCount,
                    shoppingItemCount, householdAliasCount, snoozeCount);
            return new ProductMergeResultResponse(
                    survivorId, request.absorbedId(), true, false,
                    aliasCount, receiptItemCount, observationCount, manualPurchaseCount,
                    shoppingItemCount, householdAliasCount, 0L, snoozeCount, 0L);
        }

        // Apply: simple repoint for the no-conflict tables.
        var aliasesMoved = aliasRepository.repointProduct(absorbed, survivor);
        var receiptItemsMoved = receiptItemRepository.repointProduct(absorbed, survivor);
        var observationsMoved = priceObservationRepository.repointProduct(absorbed, survivor);
        var manualPurchasesMoved = manualPurchaseRepository.repointProduct(absorbed, survivor);
        var shoppingItemsMoved = shoppingListItemRepository.repointProduct(absorbed, survivor);

        // Tables with UNIQUE (household_id, product_id): drop absorbed's
        // entries that would conflict, then repoint the rest.
        var householdAliasesDropped = householdProductAliasRepository
                .deleteAbsorbedConflictsWithSurvivor(absorbed, survivor);
        var householdAliasesMoved = householdProductAliasRepository.repointProduct(absorbed, survivor);
        var snoozesDropped = consumptionSnoozeRepository
                .deleteAbsorbedConflictsWithSurvivor(absorbed, survivor);
        var snoozesMoved = consumptionSnoozeRepository.repointProduct(absorbed, survivor);

        productRepository.delete(absorbed);

        log.info("admin.product.merge.applied survivor={} absorbed={} aliases={} receiptItems={} observations={} manualPurchases={} shoppingItems={} householdAliases={}/{} snoozes={}/{}",
                survivorId, request.absorbedId(),
                aliasesMoved, receiptItemsMoved, observationsMoved,
                manualPurchasesMoved, shoppingItemsMoved,
                householdAliasesMoved, householdAliasesDropped,
                snoozesMoved, snoozesDropped);

        return new ProductMergeResultResponse(
                survivorId, request.absorbedId(), false, true,
                aliasesMoved, receiptItemsMoved, observationsMoved, manualPurchasesMoved,
                shoppingItemsMoved, householdAliasesMoved, householdAliasesDropped,
                snoozesMoved, snoozesDropped);
    }

}
