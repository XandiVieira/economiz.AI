package com.relyon.economizai.service.extraction;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard + visibility for categorization quality. In the test context
 * the ML model isn't trained, so this measures the DICTIONARY alone — exactly
 * what dictionary edits move. The floor ratchets up as the dictionary improves;
 * raise it when accuracy rises so regressions fail the build. The live
 * GET /categorizer/benchmark reports the dictionary + trained-ML number.
 */
@SpringBootTest
@ActiveProfiles("test")
class CategorizationBenchmarkTest {

    private static final double MIN_DICTIONARY_ACCURACY_PCT = 90.0;

    @Autowired private CategorizationBenchmarkService benchmarkService;

    @Test
    void dictionaryAccuracy_meetsFloor() {
        var report = benchmarkService.run();
        System.out.printf("[benchmark] dictionary-only accuracy = %.1f%% (%d/%d). Failures:%n",
                report.accuracyPct(), report.correct(), report.total());
        report.failures().forEach(failure ->
                System.out.printf("  %-42s expected=%s got=%s [%s]%n",
                        failure.description(), failure.expected(), failure.got(), failure.source()));
        assertTrue(report.accuracyPct() >= MIN_DICTIONARY_ACCURACY_PCT,
                "dictionary accuracy " + report.accuracyPct() + "% fell below floor " + MIN_DICTIONARY_ACCURACY_PCT + "%");
    }
}
