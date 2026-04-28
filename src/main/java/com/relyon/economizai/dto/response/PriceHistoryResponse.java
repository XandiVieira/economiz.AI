package com.relyon.economizai.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PriceHistoryResponse(
        UUID productId,
        String productName,
        List<PricePoint> points
) {
    public record PricePoint(
            LocalDateTime issuedAt,
            String marketName,
            BigDecimal unitPrice,
            BigDecimal quantity
    ) {}
}
