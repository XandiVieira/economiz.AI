package com.relyon.economizai.model.enums;

/**
 * Tracks where a Product's category/genericName came from. Used for:
 * - ML training (only DICTIONARY + USER are trusted training signal — ML
 *   never trains on its own predictions to avoid feedback contamination).
 * - Auto-promotion (LEARNED_DICTIONARY entries come from stable ML
 *   predictions promoted by AutoPromotionService).
 * - Audit ("why is this product categorized as X?").
 */
public enum CategorizationSource {
    NONE,                // not categorized yet
    DICTIONARY,          // curated CSV in src/main/resources/seed/product-dictionary.csv
    LEARNED_DICTIONARY,  // auto-promoted from stable ML predictions
    ML,                  // multinomial NB inference (Phase 2.5b)
    USER                 // explicit PATCH from a user
}
