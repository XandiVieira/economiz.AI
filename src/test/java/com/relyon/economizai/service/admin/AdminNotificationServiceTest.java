package com.relyon.economizai.service.admin;

import com.relyon.economizai.dto.request.SendTestNotificationRequest;
import com.relyon.economizai.exception.UserNotFoundException;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.NotificationType;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.service.notifications.NotificationPayload;
import com.relyon.economizai.service.notifications.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminNotificationServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;

    @InjectMocks private AdminNotificationService service;

    @Test
    void throwsWhenEmailNotFound() {
        when(userRepository.findByEmail("missing@e")).thenReturn(Optional.empty());
        var request = new SendTestNotificationRequest("missing@e", null, null, null);
        assertThrows(UserNotFoundException.class, () -> service.sendTest(request));
    }

    @Test
    void dispatchesWithProvidedFieldsAndDefaults() {
        var user = User.builder().id(UUID.randomUUID()).email("u@e").build();
        when(userRepository.findByEmail("u@e")).thenReturn(Optional.of(user));

        service.sendTest(new SendTestNotificationRequest("u@e", "Custom", null, NotificationType.PROMO_PERSONAL));

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        var payload = captor.getValue();
        assertEquals(user, payload.user());
        assertEquals("Custom", payload.title());
        assertNotNull(payload.body());
        assertEquals(NotificationType.PROMO_PERSONAL, payload.type());
    }

    @Test
    void fallsBackToSystemTypeWhenOmitted() {
        var user = User.builder().id(UUID.randomUUID()).email("u@e").build();
        when(userRepository.findByEmail("u@e")).thenReturn(Optional.of(user));

        service.sendTest(new SendTestNotificationRequest("u@e", null, null, null));

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        assertEquals(NotificationType.SYSTEM, captor.getValue().type());
    }
}
