package com.relyon.economizai.service.notifications;

import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.MarketLocation;
import com.relyon.economizai.model.NotificationRule;
import com.relyon.economizai.model.PriceObservation;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.NotificationType;
import com.relyon.economizai.repository.NotificationRuleRepository;
import com.relyon.economizai.repository.NotificationRuleRepository.ProductRuleOwner;
import com.relyon.economizai.repository.ReceiptItemRepository;
import com.relyon.economizai.repository.ReceiptItemRepository.HouseholdProductPrice;
import com.relyon.economizai.repository.UserWatchedMarketRepository;
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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationRuleEngineTest {

    @Mock private NotificationRuleRepository ruleRepository;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private UserWatchedMarketRepository watchedMarketRepository;
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

    private NotificationRule defaultRule(NotificationType type, Double radiusKm, User user) {
        return NotificationRule.builder().id(UUID.randomUUID())
                .user(user).type(type).isDefault(true).radiusKm(radiusKm).active(true)
                .build();
    }

    private PriceObservation observation(BigDecimal unitPrice) {
        return PriceObservation.builder()
                .product(product()).marketCnpj(CNPJ).marketCnpjRoot("12345678")
                .marketName("Zaffari").unitPrice(unitPrice).quantity(BigDecimal.ONE)
                .observedAt(LocalDateTime.now()).build();
    }

    private PriceObservation promoObservation(BigDecimal unitPrice) {
        return PriceObservation.builder()
                .product(product()).marketCnpj(CNPJ).marketCnpjRoot("12345678")
                .marketName("Zaffari").unitPrice(unitPrice).quantity(BigDecimal.ONE)
                .promoFlag(true).observedAt(LocalDateTime.now()).build();
    }

    private MarketLocation marketAt(BigDecimal latitude, BigDecimal longitude) {
        return MarketLocation.builder().cnpj(CNPJ).latitude(latitude).longitude(longitude).build();
    }

    private HouseholdProductPrice lastPaid(UUID householdId, BigDecimal unitPrice) {
        return new HouseholdProductPrice() {
            public UUID getHouseholdId() { return householdId; }
            public BigDecimal getUnitPrice() { return unitPrice; }
        };
    }

    private ProductRuleOwner ruleOwner(UUID productId, NotificationRule rule) {
        return new ProductRuleOwner() {
            @Override
            public UUID getProductId() {
                return productId;
            }

            @Override
            public NotificationRule getRule() {
                return rule;
            }
        };
    }

    // ---- PRICE_DROP / PRICE_ABOVE ----

    @Test
    void priceDrop_firesWhenObservedAtOrBelowThreshold() {
        var rule = priceRule(NotificationType.PRICE_DROP, new BigDecimal("6.00"), null, null, owner(null, null));
        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of(rule));
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());
        when(ruleRepository.findActiveDefaultRuleOwnersWhoBought(any(), anyCollection())).thenReturn(List.of());

        engine.evaluate(List.of(observation(new BigDecimal("5.49"))), CONTRIBUTOR_HOUSEHOLD);

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        assertEquals(NotificationType.PRICE_DROP, captor.getValue().type());
        verify(ruleRepository).saveAll(any());
    }

    @Test
    void priceAbove_firesWhenObservedAtOrAboveThreshold() {
        var rule = priceRule(NotificationType.PRICE_ABOVE, new BigDecimal("6.00"), null, null, owner(null, null));
        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of(rule));
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());
        when(ruleRepository.findActiveDefaultRuleOwnersWhoBought(any(), anyCollection())).thenReturn(List.of());

        engine.evaluate(List.of(observation(new BigDecimal("6.50"))), CONTRIBUTOR_HOUSEHOLD);

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        assertEquals(NotificationType.PRICE_ABOVE, captor.getValue().type());
    }

    @Test
    void priceDrop_doesNotFireInsideCooldown() {
        var rule = priceRule(NotificationType.PRICE_DROP, new BigDecimal("6.00"), null,
                LocalDateTime.now().minusHours(1), owner(null, null));
        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of(rule));
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());
        when(ruleRepository.findActiveDefaultRuleOwnersWhoBought(any(), anyCollection())).thenReturn(List.of());

        engine.evaluate(List.of(observation(new BigDecimal("5.00"))), CONTRIBUTOR_HOUSEHOLD);

        verify(notificationService, never()).notify(any());
    }

    @Test
    void priceDrop_doesNotFireForContributingHousehold() {
        var rule = priceRule(NotificationType.PRICE_DROP, new BigDecimal("6.00"), null, null, owner(null, null));
        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of(rule));
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());
        when(ruleRepository.findActiveDefaultRuleOwnersWhoBought(any(), anyCollection())).thenReturn(List.of());

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
        when(ruleRepository.findActiveDefaultRuleOwnersWhoBought(any(), anyCollection())).thenReturn(List.of());

        engine.evaluate(List.of(observation(new BigDecimal("5.00"))), CONTRIBUTOR_HOUSEHOLD);

        verify(notificationService, never()).notify(any());
    }

    // ---- CHEAPER_MARKET ----

    @Test
    void cheaperMarket_firesWhenFarBelowLastPaidAtWatchedMarket() {
        var owner = owner(null, null);
        var rule = defaultRule(NotificationType.CHEAPER_MARKET, null, owner);
        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of());
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());
        when(ruleRepository.findActiveDefaultRuleOwnersWhoBought(eq(NotificationType.CHEAPER_MARKET), anyCollection()))
                .thenReturn(List.of(ruleOwner(PRODUCT_ID, rule)));
        lenient().when(ruleRepository.findActiveDefaultRuleOwnersWhoBought(eq(NotificationType.PROMO_COMMUNITY), anyCollection()))
                .thenReturn(List.of());
        when(receiptItemRepository.findLastPaidHistoryForProductByHouseholds(eq(PRODUCT_ID), anyCollection()))
                .thenReturn(List.of(lastPaid(OWNER_HOUSEHOLD, new BigDecimal("10.00"))));
        when(watchedMarketRepository.existsByUserIdAndMarketCnpj(owner.getId(), CNPJ)).thenReturn(true);

        engine.evaluate(List.of(observation(new BigDecimal("5.00"))), CONTRIBUTOR_HOUSEHOLD); // 50% drop

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        assertEquals(NotificationType.CHEAPER_MARKET, captor.getValue().type());
    }

    @Test
    void cheaperMarket_doesNotFireAtNonWatchedNonRadiusMarket() {
        var owner = owner(null, null);
        var rule = defaultRule(NotificationType.CHEAPER_MARKET, null, owner);
        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of());
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());
        when(ruleRepository.findActiveDefaultRuleOwnersWhoBought(eq(NotificationType.CHEAPER_MARKET), anyCollection()))
                .thenReturn(List.of(ruleOwner(PRODUCT_ID, rule)));
        lenient().when(ruleRepository.findActiveDefaultRuleOwnersWhoBought(eq(NotificationType.PROMO_COMMUNITY), anyCollection()))
                .thenReturn(List.of());
        when(receiptItemRepository.findLastPaidHistoryForProductByHouseholds(eq(PRODUCT_ID), anyCollection()))
                .thenReturn(List.of(lastPaid(OWNER_HOUSEHOLD, new BigDecimal("10.00"))));
        when(watchedMarketRepository.existsByUserIdAndMarketCnpj(owner.getId(), CNPJ)).thenReturn(false);

        engine.evaluate(List.of(observation(new BigDecimal("5.00"))), CONTRIBUTOR_HOUSEHOLD);

        verify(notificationService, never()).notify(any());
    }

    @Test
    void cheaperMarket_doesNotFireOnTinyDrop() {
        var owner = owner(null, null);
        var rule = defaultRule(NotificationType.CHEAPER_MARKET, null, owner);
        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of());
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());
        when(ruleRepository.findActiveDefaultRuleOwnersWhoBought(eq(NotificationType.CHEAPER_MARKET), anyCollection()))
                .thenReturn(List.of(ruleOwner(PRODUCT_ID, rule)));
        lenient().when(ruleRepository.findActiveDefaultRuleOwnersWhoBought(eq(NotificationType.PROMO_COMMUNITY), anyCollection()))
                .thenReturn(List.of());
        when(receiptItemRepository.findLastPaidHistoryForProductByHouseholds(eq(PRODUCT_ID), anyCollection()))
                .thenReturn(List.of(lastPaid(OWNER_HOUSEHOLD, new BigDecimal("10.00"))));
        lenient().when(watchedMarketRepository.existsByUserIdAndMarketCnpj(owner.getId(), CNPJ)).thenReturn(true);

        engine.evaluate(List.of(observation(new BigDecimal("9.90"))), CONTRIBUTOR_HOUSEHOLD); // 1% drop

        verify(notificationService, never()).notify(any());
    }

    // ---- PROMO_COMMUNITY ----

    @Test
    void communityDefault_firesOnceWhenTwoProductsBoughtOnSameReceipt() {
        var owner = owner(null, null);
        var rule = defaultRule(NotificationType.PROMO_COMMUNITY, null, owner);
        var secondProductId = UUID.randomUUID();
        var secondProduct = Product.builder().id(secondProductId).normalizedName("Café").build();

        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of());
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());
        // The SAME single rule owns both products (the user has one default rule) — the batched
        // query returns one (productId, rule) row per product, both pointing at the same rule.
        when(ruleRepository.findActiveDefaultRuleOwnersWhoBought(eq(NotificationType.PROMO_COMMUNITY), anyCollection()))
                .thenReturn(List.of(ruleOwner(PRODUCT_ID, rule), ruleOwner(secondProductId, rule)));
        when(ruleRepository.findActiveDefaultRuleOwnersWhoBought(eq(NotificationType.CHEAPER_MARKET), anyCollection()))
                .thenReturn(List.of());

        var promoForFirst = promoObservation(new BigDecimal("3.99"));
        var promoForSecond = PriceObservation.builder()
                .product(secondProduct).marketCnpj(CNPJ).marketCnpjRoot("12345678")
                .marketName("Zaffari").unitPrice(new BigDecimal("8.00")).quantity(BigDecimal.ONE)
                .promoFlag(true).observedAt(LocalDateTime.now()).build();

        engine.evaluate(List.of(promoForFirst, promoForSecond), CONTRIBUTOR_HOUSEHOLD);

        // Despite two products matching the same rule, the rule fires exactly once.
        verify(notificationService, times(1)).notify(any());
        // And the batched owners query ran exactly once per community type, not once per product.
        verify(ruleRepository, times(1))
                .findActiveDefaultRuleOwnersWhoBought(eq(NotificationType.PROMO_COMMUNITY), anyCollection());
        verify(ruleRepository, times(1))
                .findActiveDefaultRuleOwnersWhoBought(eq(NotificationType.CHEAPER_MARKET), anyCollection());
    }

    @Test
    void promoCommunity_firesWhenObservationFlaggedPromo() {
        var owner = owner(null, null);
        var rule = defaultRule(NotificationType.PROMO_COMMUNITY, null, owner);
        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of());
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());
        when(ruleRepository.findActiveDefaultRuleOwnersWhoBought(eq(NotificationType.PROMO_COMMUNITY), anyCollection()))
                .thenReturn(List.of(ruleOwner(PRODUCT_ID, rule)));
        when(ruleRepository.findActiveDefaultRuleOwnersWhoBought(eq(NotificationType.CHEAPER_MARKET), anyCollection()))
                .thenReturn(List.of());

        engine.evaluate(List.of(promoObservation(new BigDecimal("3.99"))), CONTRIBUTOR_HOUSEHOLD);

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        assertEquals(NotificationType.PROMO_COMMUNITY, captor.getValue().type());
    }
}
