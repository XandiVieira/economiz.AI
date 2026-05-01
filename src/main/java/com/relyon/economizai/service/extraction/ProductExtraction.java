package com.relyon.economizai.service.extraction;

import com.relyon.economizai.model.enums.ProductCategory;

import java.math.BigDecimal;

public record ProductExtraction(
        String genericName,
        String brand,
        BigDecimal packSize,
        String packUnit,
        ProductCategory category
) {
    public static final ProductExtraction EMPTY = new ProductExtraction(null, null, null, null, null);
}
