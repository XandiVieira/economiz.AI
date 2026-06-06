package com.relyon.economizai.dto.response;

import java.util.List;

/**
 * Categorization quality over a curated golden set (description → true category).
 * Run it before/after each enhancement to see whether accuracy improves.
 * {@code accuracyPct} is the headline number to track.
 */
public record CategorizationBenchmarkResponse(
        int total,
        int correct,
        double accuracyPct,
        int wrong,
        int uncategorized,       // subset of wrong where the cascade produced no category at all
        List<Failure> failures
) {
    public record Failure(String description, String expected, String got, String source) {}
}
