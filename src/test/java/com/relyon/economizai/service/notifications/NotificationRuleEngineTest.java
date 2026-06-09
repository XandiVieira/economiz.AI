package com.relyon.economizai.service.notifications;

import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.MarketLocation;
import com.relyon.economizai.model.NotificationRule;
import com.relyon.economizai.model.PriceObservation;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.NotificationType;
import com.relyon.economizai.repository.NotificationRuleRepository;
import com.relyon.economizai.service.geo.MarketLocationService;
import com.relyon.economizai.service.geo.MarketNameService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationRuleEngineTest {

    @Mock private NotificationRuleRepository ruleRepository;
    @Mock private MarketLocationService marketLocationService;
    @Mock private MarketNameService marketNameService;
    @Mock private NotificationService notificationService;
    @InjectMocks private NotificationRuleEngine engine;

    @BeforeEach
    void stubMarketNames() {
        lenient().when(marketNameService.resolve(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID OWNER_HOUSEHOLD = UUID.randomUUID();
    private static final UUID CONTRIBUTOR_HOUSEHOLD = UUID.randomUUID();
    private static final String CNPJ = "12345678000199";

    private Product product() {
        return Product.builder().id(PRODUCT_ID).normalizedName("Leite").build();
    }

    private User owner(BigDecimal latitude, BigDecimal longitude) {
        return User.builder().id(UUID.randomUUID()).email("owner@example.com")
                .household(Household.builder().id(OWNER_HOUSEHOLD).build())
                .homeLatitude(latitude).homeLongitude(longitude)
                .build();
    }

    private NotificationRule priceRule(NotificationType type, BigDecimal threshold,
                                       Double radiusKm, LocalDateTime lastFired, User user) {
        return NotificationRule.builder().id(UUID.randomUUID())
                .user(user).type(type).product(product())
                .thresholdPrice(threshold).radiusKm(radiusKm)
                .active(true).lastFiredAt(lastFired)
                .build();
    }

    private PriceObservation observation(BigDecimal unitPrice) {
        return PriceObservation.builder()
                .product(product()).marketCnpj(CNPJ).marketCnpjRoot("12345678")
                .marketName("Zaffari").unitPrice(unitPrice).quantity(BigDecimal.ONE)
                .observedAt(LocalDateTime.now()).build();
    }

    private MarketLocation marketAt(BigDecimal latitude, BigDecimal longitude) {
        return MarketLocation.builder().cnpj(CNPJ).latitude(latitude).longitude(longitude).build();
    }

    // ---- PRICE_DROP ----

    @Test
    void priceDrop_firesWhenObservedAtOrBelowThreshold() {
        var rule = priceRule(NotificationType.PRICE_DROP, new BigDecimal("6.00"), null, null, owner(null, null));
        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of(rule));
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());

        engine.evaluate(List.of(observation(new BigDecimal("5.49"))), CONTRIBUTOR_HOUSEHOLD);

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        assertEquals(NotificationType.PRICE_DROP, captor.getValue().type());
        verify(ruleRepository).saveAll(any());
    }

    @Test
    void priceDrop_doesNotFireAboveThreshold() {
        var rule = priceRule(NotificationType.PRICE_DROP, new BigDecimal("6.00"), null, null, owner(null, null));
        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of(rule));
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());

        engine.evaluate(List.of(observation(new BigDecimal("6.50"))), CONTRIBUTOR_HOUSEHOLD);

        verify(notificationService, never()).notify(any());
    }

    @Test
    void priceDrop_doesNotFireInsideCooldown() {
        var rule = priceRule(NotificationType.PRICE_DROP, new BigDecimal("6.00"), null,
                LocalDateTime.now().minusHours(1), owner(null, null));
        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of(rule));
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());

        engine.evaluate(List.of(observation(new BigDecimal("5.00"))), CONTRIBUTOR_HOUSEHOLD);

        verify(notificationService, never()).notify(any());
    }

    @Test
    void priceDrop_doesNotFireForContributingHousehold() {
        var rule = priceRule(NotificationType.PRICE_DROP, new BigDecimal("6.00"), null, null, owner(null, null));
        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of(rule));
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());

        engine.evaluate(List.of(observation(new BigDecimal("5.00"))), OWNER_HOUSEHOLD);

        verify(notificationService, never()).notify(any());
    }

    @Test
    void priceDrop_doesNotFireWhenMarketOutsideRadius() {
        var home = owner(new BigDecimal("-30.0331"), new BigDecimal("-51.2300"));
        var rule = priceRule(NotificationType.PRICE_DROP, new BigDecimal("6.00"), 5.0, null, home);
        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of(rule));
        // São Paulo ~850 km from Porto Alegre
        when(marketLocationService.findByCnpjs(any()))
                .thenReturn(Map.of(CNPJ, marketAt(new BigDecimal("-23.5505"), new BigDecimal("-46.6333"))));

        engine.evaluate(List.of(observation(new BigDecimal("5.00"))), CONTRIBUTOR_HOUSEHOLD);

        verify(notificationService, never()).notify(any());
    }
}
