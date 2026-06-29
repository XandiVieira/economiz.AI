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
        CategorizationSource categorizationSource,
        boolean hasPriceHistory
) {
    /**
     * Without price-history context: defaults {@code hasPriceHistory} to false.
     * Used by callers (single get, post-write echoes) that don't compute the
     * collaborative k-anonymity count.
     */
    public static ProductResponse from(Product product) {
        return from(product, false);
    }

    /**
     * {@code hasPriceHistory} is true when enough distinct households contributed
     * price observations for this product to clear k-anonymity — i.e. the
     * collaborative price history is publicly displayable. Computed in batch by
     * the search path; see {@code ProductService.search}.
     */
    public static ProductResponse from(Product product, boolean hasPriceHistory) {
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
                product.getCategorizationSource(),
                hasPriceHistory
        );
    }
}
