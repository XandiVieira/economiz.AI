package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.NotificationRule;
import com.relyon.economizai.model.enums.NotificationChannel;
import com.relyon.economizai.model.enums.NotificationType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record NotificationRuleResponse(
        UUID id,
        NotificationType type,
        UUID productId,
        String productName,
        BigDecimal thresholdPrice,
        Double radiusKm,
        Integer leadTimeDays,
        NotificationChannel channel,
        boolean active,
        boolean isDefault,
        LocalDateTime lastFiredAt,
        LocalDateTime createdAt
) {
    public static NotificationRuleResponse from(NotificationRule rule) {
        return new NotificationRuleResponse(
                rule.getId(),
                rule.getType(),
                rule.getProduct() != null ? rule.getProduct().getId() : null,
                rule.getProduct() != null ? rule.getProduct().getNormalizedName() : null,
                rule.getThresholdPrice(),
                rule.getRadiusKm(),
                rule.getLeadTimeDays(),
                rule.getChannel(),
                rule.isActive(),
                rule.isDefault(),
                rule.getLastFiredAt(),
                rule.getCreatedAt());
    }
}
