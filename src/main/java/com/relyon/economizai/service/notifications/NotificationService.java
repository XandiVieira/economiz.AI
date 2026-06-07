package com.relyon.economizai.service.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizai.model.Notification;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.NotificationChannel;
import com.relyon.economizai.model.enums.NotificationType;
import com.relyon.economizai.repository.NotificationPreferenceRepository;
import com.relyon.economizai.repository.NotificationRepository;
import com.relyon.economizai.service.subscription.Feature;
import com.relyon.economizai.service.subscription.SubscriptionGateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates notification delivery:
 *  1. Resolves the user's preferred channel for the given type
 *     (default: PUSH if user has a push token, else EMAIL).
 *  2. Picks the matching dispatcher (or no-ops gracefully).
 *  3. Persists a Notification row with the dispatch outcome — gives us
 *     an audit trail and a basis for a future user-facing inbox endpoint.
 */
@Slf4j
@Service
public class NotificationService {

    private final NotificationPreferenceRepository preferenceRepository;
    private final NotificationRepository notificationRepository;
    private final SubscriptionGateService subscriptionGate;
    private final Map<NotificationChannel, NotificationDispatcher> dispatchersByChannel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NotificationService(NotificationPreferenceRepository preferenceRepository,
                               NotificationRepository notificationRepository,
                               SubscriptionGateService subscriptionGate,
                               List<NotificationDispatcher> dispatchers) {
        this.preferenceRepository = preferenceRepository;
        this.notificationRepository = notificationRepository;
        this.subscriptionGate = subscriptionGate;
        this.dispatchersByChannel = new EnumMap<>(NotificationChannel.class);
        for (var dispatcher : dispatchers) this.dispatchersByChannel.put(dispatcher.channel(), dispatcher);
        log.info("Notification dispatchers active: {}", this.dispatchersByChannel.keySet());
    }

    @Transactional
    public void notify(NotificationPayload payload) {
        var channel = resolveChannel(payload.user(), payload.type());
        if (channel == NotificationChannel.NONE) {
            log.debug("notification.skipped user={} type={} reason=opted_out",
                    payload.user().getEmail(), payload.type());
            return;
        }
        var notification = Notification.builder()
                .user(payload.user())
                .type(payload.type())
                .channel(channel)
                .title(payload.title())
                .body(payload.body())
                .payload(serialize(payload.extras()))
                .delivered(false)
                .build();

        // FREE tier: persist the in-app inbox row but skip push/email dispatch.
        // The inbox itself is never gated; only outbound delivery is PRO.
        if (!subscriptionGate.allows(payload.user(), Feature.PUSH_AND_EMAIL_DELIVERY)) {
            notification.setFailureReason("free_tier_inbox_only");
            notificationRepository.save(notification);
            log.info("notification.inbox_only user={} type={} channel={} reason=free_tier",
                    payload.user().getEmail(), payload.type(), channel);
            return;
        }

        var dispatcher = dispatchersByChannel.get(channel);
        if (dispatcher == null) {
            notification.setFailureReason("no dispatcher registered for channel " + channel);
            notificationRepository.save(notification);
            log.warn("notification.no_dispatcher user={} type={} channel={}",
                    payload.user().getEmail(), payload.type(), channel);
            return;
        }
        var result = dispatcher.dispatch(payload);
        notification.setDelivered(result.delivered());
        notification.setDeliveredAt(result.delivered() ? LocalDateTime.now() : null);
        notification.setFailureReason(result.failureReason());
        notificationRepository.save(notification);
    }

    private NotificationChannel resolveChannel(User user, NotificationType type) {
        var preference = preferenceRepository.findByUserIdAndType(user.getId(), type);
        if (preference.isPresent()) return preference.get().getChannel();
        // System default: prefer push when token is registered, else email
        if (user.getPushDeviceToken() != null && !user.getPushDeviceToken().isBlank()) {
            return NotificationChannel.PUSH;
        }
        return NotificationChannel.EMAIL;
    }

    private String serialize(Map<String, Object> extras) {
        if (extras == null || extras.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(extras);
        } catch (Exception ex) {
            log.warn("notification.payload.serialize_failed: {}", ex.getMessage());
            return null;
        }
    }
}
