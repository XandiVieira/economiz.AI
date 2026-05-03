package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.enums.ReceiptStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lightweight per-row payload for {@code GET /receipts} and the dashboard's
 * {@code recentReceipts}. Mirrors the two-total contract of {@link ReceiptResponse}:
 *
 * <ul>
 *   <li>{@code totalAmount} — the original NF total as printed on the
 *       receipt. Audit reference. Never changes after ingestion.</li>
 *   <li>{@code householdTotalAmount} — sum of the items the household kept
 *       (excluded items removed). This is what they actually spent and the
 *       value the FE should display by default in lists / spend totals.</li>
 * </ul>
 *
 * <p>For PENDING_CONFIRMATION receipts no items are excluded yet, so both
 * totals match. They diverge after the user confirms with exclusions.
 */
public record ReceiptSummaryResponse(
        UUID id,
        String marketName,
        LocalDateTime issuedAt,
        BigDecimal totalAmount,
        BigDecimal householdTotalAmount,
        int itemCount,
        ReceiptStatus status
) {
    public static ReceiptSummaryResponse from(Receipt receipt) {
        var householdTotal = receipt.getItems().stream()
                .filter(i -> !i.isExcluded())
                .map(ReceiptItem::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ReceiptSummaryResponse(
                receipt.getId(),
                receipt.getMarketName(),
                receipt.getIssuedAt(),
                receipt.getTotalAmount(),
                householdTotal,
                receipt.getItems().size(),
                receipt.getStatus()
        );
    }
}
