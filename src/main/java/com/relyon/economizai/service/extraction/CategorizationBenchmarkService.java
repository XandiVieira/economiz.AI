package com.relyon.economizai.service.extraction;

import com.relyon.economizai.dto.response.CategorizationBenchmarkResponse;
import com.relyon.economizai.dto.response.CategorizationBenchmarkResponse.Failure;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.service.canonicalization.DescriptionNormalizer;
import com.relyon.economizai.service.extraction.ml.MlClassifierService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures extraction quality against a curated golden set
 * ({@code seed/categorization-benchmark.csv}: description → true
 * category/brand/quantity). Runs the same cascade as ingestion and reports
 * per-field accuracy, plus a SHADOW measurement of the ML model alone (so we
 * keep validating it even while it's gated out of the live cascade).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategorizationBenchmarkService {

    private static final String BENCHMARK_CSV = "seed/categorization-benchmark.csv";

    private final ProductExtractor productExtractor;
    private final MlClassifierService mlClassifier;

    public CategorizationBenchmarkResponse run() {
        var rows = loadBenchmark();
        var failures = new ArrayList<Failure>();
        var total = rows.size();
        var categoryCorrect = 0;
        var uncategorized = 0;
        var brandChecked = 0;
        var brandCorrect = 0;
        var quantityChecked = 0;
        var quantityCorrect = 0;
        var mlCorrect = 0;
        var threshold = mlClassifier.getConfidenceThreshold();

        for (var row : rows) {
            var description = row[0].trim();
            var expectedCategory = ProductCategory.valueOf(row[1].trim());
            var expectedBrand = column(row, 2);
            var expectedPackSize = column(row, 3);
            var expectedPackUnit = column(row, 4);

            var extraction = productExtractor.extract(description);

            // category (the applied cascade)
            var actualCategory = extraction.category();
            if (actualCategory == expectedCategory) {
                categoryCorrect++;
            } else {
                if (actualCategory == null) uncategorized++;
                failures.add(new Failure(description, "category", expectedCategory.name(),
                        actualCategory == null ? null : actualCategory.name(),
                        extraction.categorizationSource().name()));
            }

            // brand (only when the golden row declares one)
            if (!expectedBrand.isEmpty()) {
                brandChecked++;
                if (sameBrand(expectedBrand, extraction.brand())) {
                    brandCorrect++;
                } else {
                    failures.add(new Failure(description, "brand", expectedBrand, extraction.brand(), "BRAND_REGISTRY"));
                }
            }

            // quantity (pack size + unit, only when the golden row declares one)
            if (!expectedPackSize.isEmpty()) {
                quantityChecked++;
                if (sameQuantity(expectedPackSize, expectedPackUnit, extraction.packSize(), extraction.packUnit())) {
                    quantityCorrect++;
                } else {
                    failures.add(new Failure(description, "quantity", expectedPackSize + " " + expectedPackUnit,
                            extraction.packSize() + " " + extraction.packUnit(), "PACK_REGEX"));
                }
            }

            // ML shadow — the model alone, regardless of whether it's applied live
            var mlPrediction = mlClassifier.predictCategory(description);
            if (mlPrediction.isConfident(threshold) && mlPrediction.label() == expectedCategory) {
                mlCorrect++;
            }
        }

        var response = new CategorizationBenchmarkResponse(
                total, categoryCorrect, pct(categoryCorrect, total), total - categoryCorrect, uncategorized,
                brandChecked, brandCorrect, pct(brandCorrect, brandChecked),
                quantityChecked, quantityCorrect, pct(quantityCorrect, quantityChecked),
                total, mlCorrect, pct(mlCorrect, total),
                failures);
        log.info("categorizer.benchmark categoryPct={} brandPct={} quantityPct={} mlShadowPct={}",
                response.accuracyPct(), response.brandAccuracyPct(), response.quantityAccuracyPct(),
                response.mlCategoryAccuracyPct());
        return response;
    }

    private static String column(String[] row, int index) {
        return row.length > index ? row[index].trim() : "";
    }

    private static boolean sameBrand(String expected, String actual) {
        if (actual == null) return false;
        return DescriptionNormalizer.normalize(expected).equals(DescriptionNormalizer.normalize(actual));
    }

    private static boolean sameQuantity(String expectedSize, String expectedUnit, BigDecimal actualSize, String actualUnit) {
        if (actualSize == null) return false;
        if (new BigDecimal(expectedSize).compareTo(actualSize) != 0) return false;
        return actualUnit != null && expectedUnit.equalsIgnoreCase(actualUnit);
    }

    private static double pct(int correct, int total) {
        return total == 0 ? 0.0 : Math.round(correct * 1000.0 / total) / 10.0;
    }

    private List<String[]> loadBenchmark() {
        try {
            return CsvSeedLoader.load(BENCHMARK_CSV);
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to load " + BENCHMARK_CSV, exception);
        }
    }
}
