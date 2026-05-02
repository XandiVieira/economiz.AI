package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.Notification;
import com.relyon.economizai.model.enums.NotificationChannel;
import com.relyon.economizai.model.enums.NotificationType;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One notification as the user sees it. {@code payload} is the same JSON
 * blob that was put on the wire when the notification was generated —
 * carries extras like {@code receiptId}, {@code productId}, {@code savingsPct}
 * so the FE can deep-link from the notification card.
 */
public record NotificationResponse(
        UUID id,
        NotificationType type,
        NotificationChannel channel,
        String title,
        String body,
        String payload,
        boolean delivered,
        LocalDateTime deliveredAt,
        LocalDateTime readAt,
        LocalDateTime createdAt
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
                n.getId(),
                n.getType(),
                n.getChannel(),
                n.getTitle(),
                n.getBody(),
                n.getPayload(),
                n.isDelivered(),
                n.getDeliveredAt(),
                n.getReadAt(),
                n.getCreatedAt()
        );
    }
}
