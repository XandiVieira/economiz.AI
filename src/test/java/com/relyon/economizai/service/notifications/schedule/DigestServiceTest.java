package com.relyon.economizai.service.notifications.schedule;
import org.mockito.Spy;
import org.springframework.context.support.ResourceBundleMessageSource;
import com.relyon.economizai.service.LocalizedMessageService;

import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.NotificationRule;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.NotificationType;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.repository.NotificationRuleRepository;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.service.notifications.NotificationPayload;
import com.relyon.economizai.service.notifications.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DigestServiceTest {

    @Mock private NotificationRuleRepository ruleRepository;
    @Mock private ReceiptRepository receiptRepository;
    @Mock private NotificationService notificationService;
    private static LocalizedMessageService realMessageService() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        return new LocalizedMessageService(source);
    }

    @Spy private LocalizedMessageService messageService = realMessageService();
    @InjectMocks private DigestService service;

    private static final UUID HOUSEHOLD_ID = UUID.randomUUID();

    private User user() {
        return User.builder().id(UUID.randomUUID()).email("user@example.com")
                .household(Household.builder().id(HOUSEHOLD_ID).build())
                .build();
    }

    private NotificationRule digestRule() {
        return NotificationRule.builder().id(UUID.randomUUID())
                .user(user()).type(NotificationType.DIGEST).isDefault(true).active(true)
                .build();
    }

    @Test
    void run_sendsDigestWhenHouseholdHadActivity() {
        when(ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.DIGEST))
                .thenReturn(List.of(digestRule()));
        when(receiptRepository.countByHouseholdIdAndStatusAndConfirmedAtAfter(
                eq(HOUSEHOLD_ID), eq(ReceiptStatus.CONFIRMED), any())).thenReturn(3L);
        when(receiptRepository.sumConfirmedTotalSince(eq(HOUSEHOLD_ID), any()))
                .thenReturn(new BigDecimal("210.50"));

        service.run();

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        assertEquals(NotificationType.DIGEST, captor.getValue().type());
    }

    @Test
    void run_skipsHouseholdWithNoActivity() {
        when(ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.DIGEST))
                .thenReturn(List.of(digestRule()));
        when(receiptRepository.countByHouseholdIdAndStatusAndConfirmedAtAfter(
                eq(HOUSEHOLD_ID), eq(ReceiptStatus.CONFIRMED), any())).thenReturn(0L);

        service.run();

        verify(notificationService, never()).notify(any());
        verify(receiptRepository, never()).sumConfirmedTotalSince(any(), any());
    }
}
