package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.ReceiptItem;

import java.math.BigDecimal;
import java.util.UUID;

public record ReceiptItemResponse(
        UUID id,
        UUID productId,
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
        return from(item, (String) null);
    }

    /**
     * @param overrideLabel the household's corrected category label (global enum
     *                      name OR a custom-category name), shown in place of the
     *                      product's global category when present.
     */
    public static ReceiptItemResponse from(ReceiptItem item, String overrideLabel) {
        var friendly = item.getFriendlyDescription();
        var display = friendly != null && !friendly.isBlank() ? friendly : item.getRawDescription();
        var product = item.getProduct();
        var globalCategory = product != null && product.getCategory() != null ? product.getCategory().name() : null;
        var category = overrideLabel != null ? overrideLabel : globalCategory;
        return new ReceiptItemResponse(
                item.getId(),
                product != null ? product.getId() : null,
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
