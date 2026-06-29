package com.relyon.economizai.model.enums;

public enum EanCatalogSource {
    OPEN_FOOD_FACTS,  // imported from Open Food Facts data dump
    CURATED_IMPORT,   // manually uploaded by admin (known-good data)
    USER_CONFIRMED    // confirmed by a user correction / consensus
}
