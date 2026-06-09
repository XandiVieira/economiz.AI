package com.relyon.economizai.service.alerts;

import com.relyon.economizai.dto.request.CreatePriceAlertRequest;
import com.relyon.economizai.exception.PriceAlertNotFoundException;
import com.relyon.economizai.exception.ProductNotFoundException;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.NotificationRule;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.NotificationType;
import com.relyon.economizai.repository.NotificationRuleRepository;
import com.relyon.economizai.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceAlertServiceTest {

    @Mock private NotificationRuleRepository ruleRepository;
    @Mock private ProductRepository productRepository;
    @InjectMocks private PriceAlertService service;

    private static final UUID PRODUCT_ID = UUID.randomUUID();

    private User user() {
        return User.builder().id(UUID.randomUUID()).email("user@example.com")
                .household(Household.builder().id(UUID.randomUUID()).build())
                .build();
    }

    private Product product() {
        return Product.builder().id(PRODUCT_ID).normalizedName("Leite Italac 1L").build();
    }

    // ---- create ----

    @Test
    void create_persistsNewPriceDropRule() {
        var owner = user();
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(ruleRepository.findByUserIdAndTypeAndProductId(owner.getId(), NotificationType.PRICE_DROP, PRODUCT_ID))
                .thenReturn(Optional.empty());
        when(ruleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(owner,
                new CreatePriceAlertRequest(PRODUCT_ID, new BigDecimal("6.00"), 5.0, null));

        assertEquals(new BigDecimal("6.00"), response.thresholdPrice());
        assertEquals(5.0, response.radiusKm());
        assertTrue(response.active());
    }

    @Test
    void create_updatesExistingRuleInPlace() {
        var owner = user();
        var existing = NotificationRule.builder().id(UUID.randomUUID())
                .user(owner).type(NotificationType.PRICE_DROP).product(product())
                .thresholdPrice(new BigDecimal("9.99")).radiusKm(20.0).active(true).build();
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(ruleRepository.findByUserIdAndTypeAndProductId(owner.getId(), NotificationType.PRICE_DROP, PRODUCT_ID))
                .thenReturn(Optional.of(existing));
        when(ruleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(owner,
                new CreatePriceAlertRequest(PRODUCT_ID, new BigDecimal("6.00"), 3.0, false));

        assertEquals(new BigDecimal("6.00"), existing.getThresholdPrice());
        assertEquals(3.0, existing.getRadiusKm());
        assertFalse(existing.isActive());
        assertFalse(response.active());
    }

    @Test
    void create_unknownProductThrows() {
        var owner = user();
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());
        var request = new CreatePriceAlertRequest(PRODUCT_ID, new BigDecimal("6.00"), null, null);

        assertThrows(ProductNotFoundException.class, () -> service.create(owner, request));
    }

    // ---- list ----

    @Test
    void list_returnsOnlyPriceDropRules() {
        var owner = user();
        var priceDrop = NotificationRule.builder().id(UUID.randomUUID())
                .user(owner).type(NotificationType.PRICE_DROP).product(product())
                .thresholdPrice(new BigDecimal("4.00")).active(true).build();
        var budget = NotificationRule.builder().id(UUID.randomUUID())
                .user(owner).type(NotificationType.BUDGET)
                .thresholdPrice(new BigDecimal("500.00")).active(true).build();
        when(ruleRepository.findAllByUserIdFetchProduct(owner.getId()))
                .thenReturn(List.of(priceDrop, budget));

        var responses = service.list(owner);

        assertEquals(1, responses.size());
        assertEquals(new BigDecimal("4.00"), responses.get(0).thresholdPrice());
    }

    // ---- delete ----

    @Test
    void delete_removesPriceDropRuleWhenFound() {
        var owner = user();
        var ruleId = UUID.randomUUID();
        var existing = NotificationRule.builder().id(ruleId)
                .user(owner).type(NotificationType.PRICE_DROP).product(product()).active(true).build();
        when(ruleRepository.findByIdAndUserId(ruleId, owner.getId())).thenReturn(Optional.of(existing));

        service.delete(owner, ruleId);

        verify(ruleRepository).delete(existing);
    }

    @Test
    void delete_nonPriceDropRuleThrows() {
        var owner = user();
        var ruleId = UUID.randomUUID();
        var budget = NotificationRule.builder().id(ruleId)
                .user(owner).type(NotificationType.BUDGET).active(true).build();
        when(ruleRepository.findByIdAndUserId(ruleId, owner.getId())).thenReturn(Optional.of(budget));

        assertThrows(PriceAlertNotFoundException.class, () -> service.delete(owner, ruleId));
        verify(ruleRepository, never()).delete(any());
    }

    @Test
    void delete_unknownAlertThrows() {
        var owner = user();
        var ruleId = UUID.randomUUID();
        when(ruleRepository.findByIdAndUserId(ruleId, owner.getId())).thenReturn(Optional.empty());

        assertThrows(PriceAlertNotFoundException.class, () -> service.delete(owner, ruleId));
    }
}
