package com.relyon.economizai.model.enums;

/** What produced a categorization-quality snapshot. */
public enum CategorizationQualityTrigger {
    BENCHMARK,   // a /categorizer/benchmark run
    BACKFILL     // a /admin/products/recategorize apply
}
