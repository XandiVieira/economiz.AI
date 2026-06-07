package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.enums.ProductCategory;

import java.math.BigDecimal;
import java.util.UUID;

public record ReceiptItemResponse(
        UUID id,
        Integer lineNumber,
        String rawDescription,
        String friendlyDescription,
        String displayDescription,
        String ean,
        BigDecimal quantity,
        String unit,
        BigDecimal unitPrice,
        BigDecimal totalPrice,
        boolean excluded,
        boolean nfcePromoFlag,
        String category
) {
    public static ReceiptItemResponse from(ReceiptItem item) {
        return from(item, null);
    }

    /**
     * @param overrideCategory the household's corrected category, shown in place
     *                         of the product's global category when present.
     */
    public static ReceiptItemResponse from(ReceiptItem item, ProductCategory overrideCategory) {
        var friendly = item.getFriendlyDescription();
        var display = friendly != null && !friendly.isBlank() ? friendly : item.getRawDescription();
        var product = item.getProduct();
        var globalCategory = product != null ? product.getCategory() : null;
        var effective = overrideCategory != null ? overrideCategory : globalCategory;
        var category = effective != null ? effective.name() : null;
        return new ReceiptItemResponse(
                item.getId(),
                item.getLineNumber(),
                item.getRawDescription(),
                friendly,
                display,
                item.getEan(),
                item.getQuantity(),
                item.getUnit(),
                item.getUnitPrice(),
                item.getTotalPrice(),
                item.isExcluded(),
                item.isNfcePromoFlag(),
                category
        );
    }
}
