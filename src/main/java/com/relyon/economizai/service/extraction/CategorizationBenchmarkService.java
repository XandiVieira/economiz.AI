package com.relyon.economizai.service.extraction;

import com.relyon.economizai.dto.response.CategorizationBenchmarkResponse;
import com.relyon.economizai.dto.response.CategorizationBenchmarkResponse.Failure;
import com.relyon.economizai.model.enums.ProductCategory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;

/**
 * Measures categorization quality against a curated golden set
 * ({@code seed/categorization-benchmark.csv}: real description → true category).
 * Runs the same cascade as ingestion and reports accuracy %.
 *
 * <p>Track {@code accuracyPct} after each dictionary/model change to prove
 * improvement. Note the scope differs by environment: in tests the ML model
 * isn't trained, so this measures the dictionary alone (deterministic); the
 * live {@code /categorizer/benchmark} endpoint measures dictionary + trained ML.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategorizationBenchmarkService {

    private static final String BENCHMARK_CSV = "seed/categorization-benchmark.csv";

    private final ProductExtractor productExtractor;

    public CategorizationBenchmarkResponse run() {
        var rows = loadBenchmark();
        var failures = new ArrayList<Failure>();
        var correct = 0;
        var uncategorized = 0;
        for (var row : rows) {
            var description = row[0].trim();
            var expected = ProductCategory.valueOf(row[1].trim());
            var extraction = productExtractor.extract(description);
            var actual = extraction.category();
            if (actual == expected) {
                correct++;
                continue;
            }
            if (actual == null) uncategorized++;
            failures.add(new Failure(description, expected.name(),
                    actual == null ? null : actual.name(),
                    extraction.categorizationSource().name()));
        }
        var total = rows.size();
        var accuracyPct = total == 0 ? 0.0 : Math.round(correct * 1000.0 / total) / 10.0;
        log.info("categorizer.benchmark total={} correct={} accuracyPct={} uncategorized={}",
                total, correct, accuracyPct, uncategorized);
        return new CategorizationBenchmarkResponse(total, correct, accuracyPct, total - correct, uncategorized, failures);
    }

    private java.util.List<String[]> loadBenchmark() {
        try {
            return CsvSeedLoader.load(BENCHMARK_CSV);
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to load " + BENCHMARK_CSV, exception);
        }
    }
}
