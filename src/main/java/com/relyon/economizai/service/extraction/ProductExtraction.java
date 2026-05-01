package com.relyon.economizai.service.extraction;

import com.relyon.economizai.model.enums.CategorizationSource;
import com.relyon.economizai.model.enums.ProductCategory;

import java.math.BigDecimal;

public record ProductExtraction(
        String genericName,
        String brand,
        BigDecimal packSize,
        String packUnit,
        ProductCategory category,
        CategorizationSource categorizationSource
) {
    public static final ProductExtraction EMPTY =
            new ProductExtraction(null, null, null, null, null, CategorizationSource.NONE);

    public static ProductExtraction empty() {
        return EMPTY;
    }
}
