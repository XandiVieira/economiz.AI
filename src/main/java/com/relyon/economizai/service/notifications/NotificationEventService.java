package com.relyon.economizai.service.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizai.model.NotificationEvent;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.NotificationEventType;
import com.relyon.economizai.repository.NotificationEventRepository;
import com.relyon.economizai.service.privacy.LogMasker;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Single entry point for the notification telemetry log. Every user-feedback /
 * lifecycle signal (client-reported or server-emitted) is recorded here so a
 * future ranking/learning engine has training data. Nothing consumes these
 * rows yet — this is the telemetry foundation only.
 *
 * <p>The digest / screen / attribution phases will call this for
 * {@code SENT}/{@code DELIVERED}/{@code CONVERTED} etc.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationEventService {

    private final NotificationEventRepository repository;
    // Plain instance (not the Spring bean) to mirror NotificationService and stay
    // wireable in @WebMvcTest slices that don't load JacksonAutoConfiguration.
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Optional context for a recorded event. All fields are nullable; only the
     * user and event type are mandatory (passed separately to {@link #record}).
     */
    @Builder
    public record RecordContext(
            UUID notificationId,
            UUID productId,
            String marketCnpj,
            String channel,
            Map<String, Object> metadata
    ) {
        public static RecordContext empty() {
            return RecordContext.builder().build();
        }
    }

    @Transactional
    public NotificationEvent record(User user, NotificationEventType type, RecordContext context) {
        var safeContext = context != null ? context : RecordContext.empty();
        var event = NotificationEvent.builder()
                .user(user)
                .eventType(type)
                .notificationId(safeContext.notificationId())
                .productId(safeContext.productId())
                .marketCnpj(safeContext.marketCnpj())
                .channel(safeContext.channel())
                .metadata(serialize(safeContext.metadata()))
                .build();
        var saved = repository.save(event);
        log.info("notif.event type={} user={}", type, LogMasker.email(user.getEmail()));
        return saved;
    }

    @Transactional
    public NotificationEvent record(User user, NotificationEventType type) {
        return record(user, type, RecordContext.empty());
    }

    private String serialize(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            log.warn("notif.event.metadata_serialize_failed reason={}", ex.getMessage());
            return null;
        }
    }
}
