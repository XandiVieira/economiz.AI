package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.ReceiptItem;

import java.math.BigDecimal;
import java.util.UUID;

public record ReceiptItemResponse(
        UUID id,
        Integer lineNumber,
        String rawDescription,
        String ean,
        BigDecimal quantity,
        String unit,
        BigDecimal unitPrice,
        BigDecimal totalPrice,
        boolean excluded
) {
    public static ReceiptItemResponse from(ReceiptItem item) {
        return new ReceiptItemResponse(
                item.getId(),
                item.getLineNumber(),
                item.getRawDescription(),
                item.getEan(),
                item.getQuantity(),
                item.getUnit(),
                item.getUnitPrice(),
                item.getTotalPrice(),
                item.isExcluded()
        );
    }
}
