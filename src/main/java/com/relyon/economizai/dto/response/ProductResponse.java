package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.enums.CategorizationSource;
import com.relyon.economizai.model.enums.ProductCategory;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        String ean,
        String normalizedName,
        String genericName,
        String brand,
        ProductCategory category,
        String unit,
        BigDecimal packSize,
        String packUnit,
        CategorizationSource categorizationSource
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getEan(),
                product.getNormalizedName(),
                product.getGenericName(),
                product.getBrand(),
                product.getCategory(),
                product.getUnit(),
                product.getPackSize(),
                product.getPackUnit(),
                product.getCategorizationSource()
        );
    }
}
