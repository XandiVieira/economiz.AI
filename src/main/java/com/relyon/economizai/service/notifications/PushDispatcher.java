package com.relyon.economizai.service.notifications;

import com.relyon.economizai.model.enums.NotificationChannel;
import com.relyon.economizai.service.privacy.LogMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * V1 stub for mobile push notifications. Logs the would-be payload and
 * persists it to the notifications table (handled in NotificationService)
 * but does not actually call Firebase Cloud Messaging.
 *
 * <p>To wire FCM later, replace the {@code TODO} block with:
 * <pre>
 *   var message = Message.builder()
 *       .setToken(payload.user().getPushDeviceToken())
 *       .setNotification(Notification.builder()
 *           .setTitle(payload.title()).setBody(payload.body()).build())
 *       .putAllData(...)
 *       .build();
 *   FirebaseMessaging.getInstance().send(message);
 * </pre>
 * Add the {@code firebase-admin} Maven dependency, initialise the SDK on
 * startup with a service-account JSON, and the rest of the pipeline keeps
 * working unchanged.
 */
@Slf4j
@Component
public class PushDispatcher implements NotificationDispatcher {

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.PUSH;
    }

    @Override
    public DispatchResult dispatch(NotificationPayload payload) {
        var token = payload.user().getPushDeviceToken();
        if (token == null || token.isBlank()) {
            return DispatchResult.failed("user has no push device token registered");
        }
        // TODO(FCM): wire firebase-admin and call FirebaseMessaging.send(...)
        log.info("notification.push.stub user={} type={} title='{}' token={}",
                LogMasker.email(payload.user().getEmail()), payload.type(), payload.title(),
                LogMasker.token(token));
        return DispatchResult.ok();
    }
}
