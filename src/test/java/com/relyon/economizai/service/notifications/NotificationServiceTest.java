package com.relyon.economizai.service.notifications;

import com.relyon.economizai.model.Notification;
import com.relyon.economizai.model.NotificationPreference;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.NotificationChannel;
import com.relyon.economizai.model.enums.NotificationType;
import com.relyon.economizai.repository.NotificationPreferenceRepository;
import com.relyon.economizai.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationPreferenceRepository preferenceRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private NotificationDispatcher emailDispatcher;
    @Mock private NotificationDispatcher pushDispatcher;

    private User userWithPushToken() {
        return User.builder().id(UUID.randomUUID()).email("u@e")
                .pushDeviceToken("fcm-abc").build();
    }

    private User userWithoutPushToken() {
        return User.builder().id(UUID.randomUUID()).email("u@e").build();
    }

    private NotificationService service(boolean withEmail, boolean withPush) {
        var dispatchers = new ArrayList<NotificationDispatcher>();
        if (withEmail) {
            lenient().when(emailDispatcher.channel()).thenReturn(NotificationChannel.EMAIL);
            dispatchers.add(emailDispatcher);
        }
        if (withPush) {
            lenient().when(pushDispatcher.channel()).thenReturn(NotificationChannel.PUSH);
            dispatchers.add(pushDispatcher);
        }
        return new NotificationService(preferenceRepository, notificationRepository, dispatchers);
    }

    private NotificationPayload payload(User user, NotificationType type) {
        return new NotificationPayload(user, type, "Title", "Body", Map.of("k", "v"));
    }

    @Test
    void preferencesPushWhenUserHasTokenAndNoExplicitPreference() {
        var user = userWithPushToken();
        when(preferenceRepository.findByUserIdAndType(any(), any())).thenReturn(Optional.empty());
        when(pushDispatcher.dispatch(any())).thenReturn(NotificationDispatcher.DispatchResult.ok());
        var svc = service(true, true);

        svc.notify(payload(user, NotificationType.PROMO_PERSONAL));

        verify(pushDispatcher).dispatch(any());
        verify(emailDispatcher, never()).dispatch(any());
        var captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertEquals(NotificationChannel.PUSH, captor.getValue().getChannel());
        assertTrue(captor.getValue().isDelivered());
    }

    @Test
    void fallsBackToEmailWhenNoPushToken() {
        var user = userWithoutPushToken();
        when(preferenceRepository.findByUserIdAndType(any(), any())).thenReturn(Optional.empty());
        when(emailDispatcher.dispatch(any())).thenReturn(NotificationDispatcher.DispatchResult.ok());
        var svc = service(true, true);

        svc.notify(payload(user, NotificationType.PROMO_PERSONAL));

        verify(emailDispatcher).dispatch(any());
        verify(pushDispatcher, never()).dispatch(any());
    }

    @Test
    void respectsExplicitNonePreferenceAndDoesNotDispatch() {
        var user = userWithPushToken();
        when(preferenceRepository.findByUserIdAndType(any(), any())).thenReturn(Optional.of(
                NotificationPreference.builder().user(user).type(NotificationType.PROMO_PERSONAL)
                        .channel(NotificationChannel.NONE).build()));
        var svc = service(true, true);

        svc.notify(payload(user, NotificationType.PROMO_PERSONAL));

        verify(emailDispatcher, never()).dispatch(any());
        verify(pushDispatcher, never()).dispatch(any());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void persistsFailureWhenDispatchFails() {
        var user = userWithPushToken();
        when(preferenceRepository.findByUserIdAndType(any(), any())).thenReturn(Optional.empty());
        when(pushDispatcher.dispatch(any())).thenReturn(NotificationDispatcher.DispatchResult.failed("no token"));
        var svc = service(true, true);

        svc.notify(payload(user, NotificationType.PROMO_PERSONAL));

        var captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertEquals(false, captor.getValue().isDelivered());
        assertEquals("no token", captor.getValue().getFailureReason());
    }

    @Test
    void persistsFailureWhenNoDispatcherForRequestedChannel() {
        var user = userWithPushToken();
        when(preferenceRepository.findByUserIdAndType(any(), any())).thenReturn(Optional.of(
                NotificationPreference.builder().user(user).type(NotificationType.PROMO_PERSONAL)
                        .channel(NotificationChannel.EMAIL).build()));
        var svc = service(false, true); // no email dispatcher (e.g., SMTP not configured)

        svc.notify(payload(user, NotificationType.PROMO_PERSONAL));

        var captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertEquals(false, captor.getValue().isDelivered());
        assertNotNull(captor.getValue().getFailureReason());
    }
}
