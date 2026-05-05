package com.relyon.economizai.service.canonicalization;

import com.relyon.economizai.config.MdcContextFilter;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.ProductAlias;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.repository.ProductAliasRepository;
import com.relyon.economizai.repository.ProductRepository;
import com.relyon.economizai.service.HouseholdProductAliasService;
import com.relyon.economizai.service.extraction.ProductExtraction;
import com.relyon.economizai.service.extraction.ProductExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static com.relyon.economizai.service.canonicalization.JaroWinklerSimilarity.score;

@Slf4j
@Service
@RequiredArgsConstructor
public class CanonicalizationService {

    /**
     * Minimum Jaro-Winkler score for a candidate alias to be accepted as
     * the same product. Tuned conservatively — at 0.85 a typo or
     * abbreviation passes ("ARROZ TIO J 5KG" vs "ARROZ TIO JOAO 5KG"), but
     * unrelated products with shared prefixes ("ARROZ" vs "ARROZ DOCE")
     * stay separate.
     */
    private static final double FUZZY_MATCH_THRESHOLD = 0.85;

    private final ProductRepository productRepository;
    private final ProductAliasRepository aliasRepository;
    private final ProductExtractor productExtractor;
    private final HouseholdProductAliasService householdProductAliasService;

    @Transactional
    public CanonicalizationOutcome canonicalize(Receipt receipt) {
        var matched = 0;
        var created = 0;
        var unmatched = 0;
        for (var item : receipt.getItems()) {
            if (item.isExcluded()) continue;
            MDC.put(MdcContextFilter.ITEM_ID, abbrev(item.getId()));
            try {
                var result = canonicalizeItem(item);
                switch (result) {
                    case MATCHED -> matched++;
                    case CREATED -> created++;
                    case UNMATCHED -> unmatched++;
                }
                applyHouseholdFriendlyName(receipt, item);
            } finally {
                MDC.remove(MdcContextFilter.ITEM_ID);
            }
        }
        log.info("canonicalize done matched={} created={} unmatched={}", matched, created, unmatched);
        return new CanonicalizationOutcome(matched, created, unmatched);
    }

    private void applyHouseholdFriendlyName(Receipt receipt, ReceiptItem item) {
        // Skip if user already typed a name on this item, or item didn't get
        // linked to a Product, or there's no household memory for this product.
        if (item.getFriendlyDescription() != null && !item.getFriendlyDescription().isBlank()) return;
        if (item.getProduct() == null) return;
        var remembered = householdProductAliasService.findFor(receipt.getHousehold(), item.getProduct());
        if (remembered != null) {
            item.setFriendlyDescription(remembered);
            log.info("item.inherited_friendly_name product={} name='{}'",
                    abbrev(item.getProduct().getId()), remembered);
        }
    }

    private ItemResult canonicalizeItem(ReceiptItem item) {
        if (item.getProduct() != null) {
            log.info("item.skip already linked product={}", abbrev(item.getProduct().getId()));
            return ItemResult.MATCHED;
        }

        var normalized = DescriptionNormalizer.normalize(item.getRawDescription());

        if (hasEan(item)) {
            var byEan = productRepository.findByEan(item.getEan());
            if (byEan.isPresent()) {
                item.setProduct(byEan.get());
                ensureAlias(byEan.get(), item.getRawDescription(), normalized);
                log.info("item.matched_by_ean ean={} product={}", item.getEan(), abbrev(byEan.get().getId()));
                return ItemResult.MATCHED;
            }
            var extraction = productExtractor.extract(item.getRawDescription());
            var dedup = tryMetadataDedup(extraction);
            if (dedup != null) {
                item.setProduct(dedup);
                ensureAlias(dedup, item.getRawDescription(), normalized);
                log.info("item.matched_by_metadata ean={} product={} brand={} pack={}{}",
                        item.getEan(), abbrev(dedup.getId()), extraction.brand(),
                        extraction.packSize(), extraction.packUnit());
                return ItemResult.MATCHED;
            }
            var created = productRepository.save(buildEnrichedProduct(item, extraction));
            item.setProduct(created);
            ensureAlias(created, item.getRawDescription(), normalized);
            log.info("item.created_from_ean ean={} product={} description='{}' extracted={}",
                    item.getEan(), abbrev(created.getId()), item.getRawDescription(), extraction);
            return ItemResult.CREATED;
        }

        var byAlias = aliasRepository.findByNormalizedDescription(normalized);
        if (byAlias.isPresent()) {
            item.setProduct(byAlias.get().getProduct());
            log.info("item.matched_by_alias product={} normalized='{}'",
                    abbrev(byAlias.get().getProduct().getId()), normalized);
            return ItemResult.MATCHED;
        }
        var fuzzy = tryFuzzyAliasMatch(item, normalized);
        if (fuzzy != null) {
            item.setProduct(fuzzy.getProduct());
            ensureAlias(fuzzy.getProduct(), item.getRawDescription(), normalized);
            return ItemResult.MATCHED;
        }
        log.info("item.unmatched description='{}' (no EAN, no alias) — needs review", item.getRawDescription());
        return ItemResult.UNMATCHED;
    }

    /**
     * Dedup gate before creating a new {@link Product} for an unknown EAN.
     * Some markets (especially small mercadinhos) emit internal pseudo-EANs
     * for the same physical product, which would otherwise inflate the
     * catalog with duplicates. When all four metadata dimensions are
     * extracted with confidence and an existing product matches them
     * exactly, link to it instead of creating a new one. The new EAN is
     * deliberately NOT propagated onto the existing product — keeping
     * {@code Product.ean} as a single canonical code per row simplifies
     * downstream queries; the new alias takes care of future descriptions.
     *
     * <p>Returns null (→ fall through to create-new) when:
     * <ul>
     *   <li>any of genericName / brand / packSize / packUnit is missing,</li>
     *   <li>no product matches the full profile.</li>
     * </ul>
     * If multiple match (already-duplicated catalog rows), picks the first —
     * the admin merge tool is the right place to consolidate the rest.
     */
    private Product tryMetadataDedup(ProductExtraction extraction) {
        if (extraction.genericName() == null || extraction.brand() == null
                || extraction.packSize() == null || extraction.packUnit() == null) {
            return null;
        }
        var matches = productRepository.findByMetadata(
                extraction.genericName(), extraction.brand(),
                extraction.packSize(), extraction.packUnit());
        return matches.isEmpty() ? null : matches.get(0);
    }

    /**
     * Last-resort fuzzy alias match. Runs only when EAN is absent AND no
     * exact-alias match was found. Restricts the candidate pool to aliases
     * of products with the same extracted (genericName, packSize, packUnit)
     * profile, then picks the candidate with the highest Jaro-Winkler score
     * if it clears {@link #FUZZY_MATCH_THRESHOLD}. Returns null when:
     *
     * <ul>
     *   <li>any of the three metadata dimensions is missing — without them
     *       the candidate filter is too loose (would scan unrelated products).</li>
     *   <li>no candidate clears the threshold.</li>
     * </ul>
     */
    private ProductAlias tryFuzzyAliasMatch(ReceiptItem item, String normalized) {
        var extraction = productExtractor.extract(item.getRawDescription());
        if (extraction.genericName() == null || extraction.packSize() == null || extraction.packUnit() == null) {
            return null;
        }
        var candidates = aliasRepository.findCandidatesByProductMetadata(
                extraction.genericName(), extraction.packSize(), extraction.packUnit());
        ProductAlias best = null;
        var bestScore = 0.0;
        for (var candidate : candidates) {
            var s = score(normalized, candidate.getNormalizedDescription());
            if (s > bestScore) {
                bestScore = s;
                best = candidate;
            }
        }
        if (best == null || bestScore < FUZZY_MATCH_THRESHOLD) {
            return null;
        }
        log.info("item.matched_by_fuzzy product={} normalized='{}' candidate='{}' score={}",
                abbrev(best.getProduct().getId()), normalized, best.getNormalizedDescription(),
                String.format("%.3f", bestScore));
        return best;
    }

    private boolean hasEan(ReceiptItem item) {
        return item.getEan() != null && !item.getEan().isBlank();
    }

    private Product buildEnrichedProduct(ReceiptItem item, ProductExtraction extraction) {
        return Product.builder()
                .ean(item.getEan())
                .normalizedName(item.getRawDescription())
                .unit(item.getUnit())
                .genericName(extraction.genericName())
                .brand(extraction.brand())
                .category(extraction.category())
                .packSize(extraction.packSize())
                .packUnit(extraction.packUnit())
                .categorizationSource(extraction.categorizationSource())
                .build();
    }

    private void ensureAlias(Product product, String rawDescription, String normalized) {
        if (normalized.isBlank() || aliasRepository.existsByNormalizedDescription(normalized)) {
            return;
        }
        aliasRepository.save(ProductAlias.builder()
                .product(product)
                .rawDescription(rawDescription)
                .normalizedDescription(normalized)
                .build());
    }

    private static String abbrev(UUID id) {
        return id == null ? "" : id.toString().substring(0, 8);
    }

    public enum ItemResult { MATCHED, CREATED, UNMATCHED }

    public record CanonicalizationOutcome(int matched, int created, int unmatched) {}
}
