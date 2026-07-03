package com.relyon.economizai.service.extraction;

import com.relyon.economizai.model.EanCatalogEntry;
import com.relyon.economizai.model.enums.EanCatalogSource;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.repository.EanCatalogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Manages the EAN product catalog — a local DB table seeded from Open Food
 * Facts (or admin imports) that maps GTIN/EAN barcodes to product categories.
 *
 * <p>This is queried in the canonicalization cascade as step A2: after we
 * confirm an EAN isn't already in our own products table, we check here
 * before falling through to the keyword dictionary + ML.</p>
 *
 * <p><b>Seeding:</b> download the Open Food Facts Brazil CSV dump and call
 * {@link #bulkImport} with the parsed rows. The mapper
 * {@link OpenFoodFactsCategoryMapper} converts OPF category tags to our enum.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EanCatalogService {

    private final EanCatalogRepository eanCatalogRepository;

    /** Step A2 in the cascade — O(1) primary-key lookup. */
    public Optional<EanCatalogEntry> lookup(String ean) {
        return eanCatalogRepository.findByEan(ean);
    }

    /**
     * Bulk-upserts EAN entries into the catalog.  Existing EANs are updated
     * (category/genericName/brand may improve with better data sources);
     * new ones are inserted.  Rows with null or blank EAN are skipped.
     */
    @Transactional
    public BulkImportOutcome bulkImport(List<EanImportRequest> entries) {
        if (entries == null || entries.isEmpty()) return new BulkImportOutcome(0, 0);

        var eans = entries.stream()
                .filter(req -> req.ean() != null && !req.ean().isBlank())
                .map(EanImportRequest::ean)
                .toList();

        var existingByEan = eanCatalogRepository.findByEanIn(eans).stream()
                .collect(java.util.stream.Collectors.toMap(EanCatalogEntry::getEan, entry -> entry));

        var toSave = new ArrayList<EanCatalogEntry>();
        var skipped = 0;

        for (var req : entries) {
            if (req.ean() == null || req.ean().isBlank()) {
                skipped++;
                continue;
            }
            var entry = existingByEan.getOrDefault(req.ean(),
                    EanCatalogEntry.builder().ean(req.ean()).build());
            if (req.genericName() != null) entry.setGenericName(req.genericName());
            if (req.brand() != null) entry.setBrand(req.brand());
            if (req.category() != null) entry.setCategory(req.category());
            entry.setSource(req.source() != null ? req.source() : EanCatalogSource.CURATED_IMPORT);
            toSave.add(entry);
        }

        eanCatalogRepository.saveAll(toSave);
        log.info("ean_catalog.bulk_import imported={} skipped={} total={}",
                toSave.size(), skipped, eanCatalogRepository.count());
        return new BulkImportOutcome(toSave.size(), skipped);
    }

    /**
     * Bulk-imports raw Open Food Facts rows, mapping OPF category tags to our
     * enum server-side (via {@link OpenFoodFactsCategoryMapper}) so the import
     * client stays dumb — it just forwards what the OFF API returns. Rows with
     * no EAN are skipped.
     */
    @Transactional
    public BulkImportOutcome bulkImportOpenFoodFacts(List<OpenFoodFactsRow> rows) {
        if (rows == null || rows.isEmpty()) return new BulkImportOutcome(0, 0);
        var mapped = rows.stream()
                .map(row -> parseOpenFoodFactsRow(row.code(), row.productName(), row.brands(), row.categoryTags()))
                .toList();
        return bulkImport(mapped);
    }

    /**
     * Parses a single Open Food Facts CSV row (header fields expected:
     * {@code code,product_name,brands,categories_tags}) and imports it.
     * Intended for batch use — caller collects rows and calls
     * {@link #bulkImport} with the full list for performance.
     */
    public static EanImportRequest parseOpenFoodFactsRow(String ean, String productName,
                                                          String brands, String categoryTags) {
        var category = OpenFoodFactsCategoryMapper.map(categoryTags);
        return new EanImportRequest(
                ean,
                productName != null && !productName.isBlank() ? productName.trim() : null,
                brands != null && !brands.isBlank() ? brands.split(",")[0].trim() : null,
                category == ProductCategory.OTHER ? null : category,  // null = unknown, not forced OTHER
                EanCatalogSource.OPEN_FOOD_FACTS
        );
    }

    /** Raw Open Food Facts row as returned by its search API. */
    public record OpenFoodFactsRow(String code, String productName, String brands, String categoryTags) {}

    public record EanImportRequest(
            String ean,
            String genericName,
            String brand,
            ProductCategory category,
            EanCatalogSource source) {}

    public record BulkImportOutcome(int imported, int skipped) {}
}
