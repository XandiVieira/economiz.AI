package com.relyon.economizai.service.admin;

import com.relyon.economizai.dto.request.SendTestNotificationRequest;
import com.relyon.economizai.exception.UserNotFoundException;
import com.relyon.economizai.model.enums.NotificationType;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.service.notifications.NotificationPayload;
import com.relyon.economizai.service.notifications.NotificationService;
import com.relyon.economizai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Admin-only test harness for the notification pipeline. Resolves a user
 * by email and hands a payload to {@link NotificationService} — useful for
 * smoke-testing FCM/SMTP wiring on demand without waiting for a natural
 * trigger (promo detection, stockout, etc).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminNotificationService {

    private static final String DEFAULT_TITLE = "economizai test";
    private static final String DEFAULT_BODY = "If you can read this, push notifications are working.";

    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public void sendTest(SendTestNotificationRequest request) {
        var user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new UserNotFoundException(request.email()));
        var type = request.type() != null ? request.type() : NotificationType.SYSTEM;
        var title = request.title() != null && !request.title().isBlank() ? request.title() : DEFAULT_TITLE;
        var body = request.body() != null && !request.body().isBlank() ? request.body() : DEFAULT_BODY;
        log.info("admin.notification.test_send target={} type={} hasPushToken={}",
                LogMasker.email(user.getEmail()), type,
                user.getPushDeviceToken() != null && !user.getPushDeviceToken().isBlank());
        notificationService.notify(new NotificationPayload(user, type, title, body, Map.of("source", "admin_test")));
    }
}
