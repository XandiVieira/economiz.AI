package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.enums.ReceiptStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record ReceiptSummaryResponse(
        UUID id,
        String marketName,
        LocalDateTime issuedAt,
        BigDecimal totalAmount,
        int itemCount,
        ReceiptStatus status
) {
    public static ReceiptSummaryResponse from(Receipt receipt) {
        return new ReceiptSummaryResponse(
                receipt.getId(),
                receipt.getMarketName(),
                receipt.getIssuedAt(),
                receipt.getTotalAmount(),
                receipt.getItems().size(),
                receipt.getStatus()
        );
    }
}
