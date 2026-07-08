package com.relyon.economizai.service.extraction;

import com.relyon.economizai.service.extraction.EanCatalogService.OpenFoodFactsRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Fills catalog gaps on demand: for barcodes our imported catalog doesn't know,
 * it queries the live OFF-family API ({@link OpenFoodFactsApiClient}) and
 * caches-through the result into the catalog, so the next lookup (and every
 * future receipt with that product) resolves locally.
 *
 * <p>Called from the UNTRANSACTED ingestion phase ({@code ReceiptIngestionService},
 * after parse, before persist) — the HTTP calls must never run inside a DB
 * transaction, and must never break ingest, so this is strictly best-effort.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EanCatalogEnrichmentService {

    /** Safety cap so a pathological receipt can't fan out into hundreds of API calls. */
    private static final int MAX_LOOKUPS_PER_RECEIPT = 40;

    private final OpenFoodFactsApiClient apiClient;
    private final EanCatalogService eanCatalogService;

    public boolean isEnabled() {
        return apiClient.isEnabled();
    }

    /**
     * For each distinct EAN we can't yet categorize — missing from the catalog OR
     * present but with a null category (e.g. an old import that predates the pt:
     * tag mapping) — fetch it live and cache-through, upserting the row. Returns
     * the number of catalog rows written.
     *
     * <p>Re-fetching null-category rows lets the catalog self-heal: a row imported
     * before we understood its OFF tags gets a category the next time the product
     * is bought. Rows OFF genuinely can't categorize stay null and may be
     * re-fetched on a later purchase — bounded by the per-receipt cap.
     */
    public int enrichMissing(Collection<String> eans) {
        if (!apiClient.isEnabled() || eans == null || eans.isEmpty()) return 0;
        var missing = eans.stream()
                .filter(ean -> ean != null && !ean.isBlank())
                .distinct()
                .filter(this::needsCategory)
                .limit(MAX_LOOKUPS_PER_RECEIPT)
                .toList();
        if (missing.isEmpty()) return 0;

        var fetched = new ArrayList<OpenFoodFactsRow>();
        for (var ean : missing) {
            apiClient.fetch(ean).ifPresent(fetched::add);
        }
        if (fetched.isEmpty()) {
            log.info("ean_catalog.enrich looked_up={} found=0", missing.size());
            return 0;
        }
        var outcome = eanCatalogService.bulkImportOpenFoodFacts(fetched);
        log.info("ean_catalog.enrich looked_up={} found={} written={}",
                missing.size(), fetched.size(), outcome.imported());
        return outcome.imported();
    }

    /** True when the catalog has no usable category for this EAN yet. */
    private boolean needsCategory(String ean) {
        var existing = eanCatalogService.lookup(ean);
        return existing.isEmpty() || existing.get().getCategory() == null;
    }
}
