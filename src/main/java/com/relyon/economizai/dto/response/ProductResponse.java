package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.enums.ProductCategory;

import java.util.UUID;

public record ProductResponse(
        UUID id,
        String ean,
        String normalizedName,
        String brand,
        ProductCategory category,
        String unit
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getEan(),
                product.getNormalizedName(),
                product.getBrand(),
                product.getCategory(),
                product.getUnit()
        );
    }
}
