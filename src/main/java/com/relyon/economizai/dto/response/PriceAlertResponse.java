package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.PriceAlert;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record PriceAlertResponse(
        UUID id,
        UUID productId,
        String productName,
        BigDecimal thresholdPrice,
        Double radiusKm,
        boolean active,
        LocalDateTime lastFiredAt,
        LocalDateTime createdAt
) {
    public static PriceAlertResponse from(PriceAlert alert) {
        return new PriceAlertResponse(
                alert.getId(),
                alert.getProduct().getId(),
                alert.getProduct().getNormalizedName(),
                alert.getThresholdPrice(),
                alert.getRadiusKm(),
                alert.isActive(),
                alert.getLastFiredAt(),
                alert.getCreatedAt());
    }
}
