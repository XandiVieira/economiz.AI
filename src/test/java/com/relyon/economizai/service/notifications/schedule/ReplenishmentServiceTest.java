package com.relyon.economizai.service.notifications.schedule;
import org.mockito.Spy;
import org.springframework.context.support.ResourceBundleMessageSource;
import com.relyon.economizai.service.LocalizedMessageService;

import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.NotificationRule;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.NotificationType;
import com.relyon.economizai.repository.NotificationRuleRepository;
import com.relyon.economizai.repository.ReceiptItemRepository;
import com.relyon.economizai.service.notifications.NotificationPayload;
import com.relyon.economizai.service.notifications.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReplenishmentServiceTest {

    @Mock private NotificationRuleRepository ruleRepository;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private NotificationService notificationService;
    private static LocalizedMessageService realMessageService() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        return new LocalizedMessageService(source);
    }

    @Spy private LocalizedMessageService messageService = realMessageService();
    @InjectMocks private ReplenishmentService service;

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID HOUSEHOLD_ID = UUID.randomUUID();

    private User user() {
        return User.builder().id(UUID.randomUUID()).email("user@example.com")
                .household(Household.builder().id(HOUSEHOLD_ID).build())
                .build();
    }

    private NotificationRule stockoutRule(LocalDateTime lastFired, Integer leadTimeDays) {
        return NotificationRule.builder().id(UUID.randomUUID())
                .user(user()).type(NotificationType.STOCKOUT)
                .product(Product.builder().id(PRODUCT_ID).normalizedName("Café").build())
                .leadTimeDays(leadTimeDays).active(true).lastFiredAt(lastFired)
                .build();
    }

    private ReceiptItem purchaseOn(LocalDateTime issuedAt) {
        return ReceiptItem.builder()
                .receipt(Receipt.builder().issuedAt(issuedAt).build())
                .build();
    }

    @Test
    void run_firesWhenWithinLeadTimeWindow() {
        var rule = stockoutRule(null, 3);
        when(ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.STOCKOUT))
                .thenReturn(List.of(rule));
        // Bought every ~7 days; last buy 6 days ago → predicted runout in ~1 day, within 3-day lead time.
        when(receiptItemRepository.findHouseholdHistoryForProduct(PRODUCT_ID, HOUSEHOLD_ID))
                .thenReturn(List.of(
                        purchaseOn(LocalDateTime.now().minusDays(13)),
                        purchaseOn(LocalDateTime.now().minusDays(6))));

        service.run();

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        assertEquals(NotificationType.STOCKOUT, captor.getValue().type());
        verify(ruleRepository).saveAll(any());
    }

    @Test
    void run_skipsWhenFewerThanTwoDistinctPurchaseDates() {
        var rule = stockoutRule(null, 3);
        when(ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.STOCKOUT))
                .thenReturn(List.of(rule));
        when(receiptItemRepository.findHouseholdHistoryForProduct(PRODUCT_ID, HOUSEHOLD_ID))
                .thenReturn(List.of(purchaseOn(LocalDateTime.now().minusDays(2))));

        service.run();

        verify(notificationService, never()).notify(any());
    }

    @Test
    void run_skipsRuleInsideCooldown() {
        var rule = stockoutRule(LocalDateTime.now().minusDays(1), 3);
        when(ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.STOCKOUT))
                .thenReturn(List.of(rule));
        lenient().when(receiptItemRepository.findHouseholdHistoryForProduct(PRODUCT_ID, HOUSEHOLD_ID))
                .thenReturn(List.of(
                        purchaseOn(LocalDateTime.now().minusDays(13)),
                        purchaseOn(LocalDateTime.now().minusDays(6))));

        service.run();

        verify(notificationService, never()).notify(any());
    }
}
