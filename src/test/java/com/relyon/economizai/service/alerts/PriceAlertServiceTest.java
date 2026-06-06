package com.relyon.economizai.service.alerts;

import com.relyon.economizai.dto.request.CreatePriceAlertRequest;
import com.relyon.economizai.exception.PriceAlertNotFoundException;
import com.relyon.economizai.exception.ProductNotFoundException;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.MarketLocation;
import com.relyon.economizai.model.PriceAlert;
import com.relyon.economizai.model.PriceObservation;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.NotificationType;
import com.relyon.economizai.repository.PriceAlertRepository;
import com.relyon.economizai.repository.ProductRepository;
import com.relyon.economizai.service.geo.MarketLocationService;
import com.relyon.economizai.service.notifications.NotificationPayload;
import com.relyon.economizai.service.notifications.NotificationService;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceAlertServiceTest {

    @Mock private PriceAlertRepository alertRepository;
    @Mock private ProductRepository productRepository;
    @Mock private MarketLocationService marketLocationService;
    @Mock private NotificationService notificationService;
    @InjectMocks private PriceAlertService service;

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID OWNER_HOUSEHOLD = UUID.randomUUID();
    private static final UUID CONTRIBUTOR_HOUSEHOLD = UUID.randomUUID();
    private static final String CNPJ = "12345678000199";

    private Product product() {
        return Product.builder().id(PRODUCT_ID).normalizedName("Leite Italac 1L").build();
    }

    private User owner(BigDecimal lat, BigDecimal lng) {
        return User.builder().id(UUID.randomUUID()).email("owner@e")
                .household(Household.builder().id(OWNER_HOUSEHOLD).build())
                .homeLatitude(lat).homeLongitude(lng)
                .build();
    }

    private PriceAlert alert(BigDecimal threshold, Double radiusKm, LocalDateTime lastFired, User owner) {
        return PriceAlert.builder().id(UUID.randomUUID())
                .user(owner).product(product())
                .thresholdPrice(threshold).radiusKm(radiusKm)
                .active(true).lastFiredAt(lastFired)
                .build();
    }

    private PriceObservation observation(BigDecimal unitPrice) {
        return PriceObservation.builder()
                .product(product()).marketCnpj(CNPJ).marketCnpjRoot("12345678")
                .marketName("Zaffari Centro").unitPrice(unitPrice).quantity(BigDecimal.ONE)
                .observedAt(LocalDateTime.now()).build();
    }

    private MarketLocation marketAt(BigDecimal lat, BigDecimal lng) {
        return MarketLocation.builder().cnpj(CNPJ).latitude(lat).longitude(lng).build();
    }

    // ---- evaluate ----

    @Test
    void fires_when_price_at_or_below_threshold_no_radius() {
        var alert = alert(new BigDecimal("6.00"), null, null, owner(null, null));
        when(alertRepository.findActiveByProductIdInFetchUser(any())).thenReturn(List.of(alert));
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());

        service.evaluate(List.of(observation(new BigDecimal("5.49"))), CONTRIBUTOR_HOUSEHOLD);

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        assertEquals(NotificationType.PRICE_DROP, captor.getValue().type());
        verify(alertRepository).saveAll(any());
    }

    @Test
    void does_not_fire_when_price_above_threshold() {
        var alert = alert(new BigDecimal("6.00"), null, null, owner(null, null));
        when(alertRepository.findActiveByProductIdInFetchUser(any())).thenReturn(List.of(alert));

        service.evaluate(List.of(observation(new BigDecimal("6.01"))), CONTRIBUTOR_HOUSEHOLD);

        verify(notificationService, never()).notify(any());
    }

    @Test
    void does_not_fire_for_contributing_household() {
        var alert = alert(new BigDecimal("6.00"), null, null, owner(null, null));
        when(alertRepository.findActiveByProductIdInFetchUser(any())).thenReturn(List.of(alert));

        // contributor IS the alert owner's household
        service.evaluate(List.of(observation(new BigDecimal("5.00"))), OWNER_HOUSEHOLD);

        verify(notificationService, never()).notify(any());
    }

    @Test
    void does_not_fire_inside_cooldown_window() {
        var alert = alert(new BigDecimal("6.00"), null, LocalDateTime.now().minusHours(1), owner(null, null));
        when(alertRepository.findActiveByProductIdInFetchUser(any())).thenReturn(List.of(alert));

        service.evaluate(List.of(observation(new BigDecimal("5.00"))), CONTRIBUTOR_HOUSEHOLD);

        verify(notificationService, never()).notify(any());
    }

    @Test
    void fires_after_cooldown_expired() {
        var alert = alert(new BigDecimal("6.00"), null, LocalDateTime.now().minusDays(2), owner(null, null));
        when(alertRepository.findActiveByProductIdInFetchUser(any())).thenReturn(List.of(alert));
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());

        service.evaluate(List.of(observation(new BigDecimal("5.00"))), CONTRIBUTOR_HOUSEHOLD);

        verify(notificationService).notify(any());
    }

    @Test
    void fires_when_market_within_radius() {
        var home = owner(new BigDecimal("-30.0331"), new BigDecimal("-51.2300"));
        var alert = alert(new BigDecimal("6.00"), 5.0, null, home);
        when(alertRepository.findActiveByProductIdInFetchUser(any())).thenReturn(List.of(alert));
        // market essentially at home -> 0 km
        when(marketLocationService.findByCnpjs(any()))
                .thenReturn(Map.of(CNPJ, marketAt(new BigDecimal("-30.0331"), new BigDecimal("-51.2300"))));

        service.evaluate(List.of(observation(new BigDecimal("5.00"))), CONTRIBUTOR_HOUSEHOLD);

        verify(notificationService).notify(any());
    }

    @Test
    void does_not_fire_when_market_outside_radius() {
        var home = owner(new BigDecimal("-30.0331"), new BigDecimal("-51.2300"));
        var alert = alert(new BigDecimal("6.00"), 5.0, null, home);
        when(alertRepository.findActiveByProductIdInFetchUser(any())).thenReturn(List.of(alert));
        // São Paulo ~850 km from Porto Alegre
        when(marketLocationService.findByCnpjs(any()))
                .thenReturn(Map.of(CNPJ, marketAt(new BigDecimal("-23.5505"), new BigDecimal("-46.6333"))));

        service.evaluate(List.of(observation(new BigDecimal("5.00"))), CONTRIBUTOR_HOUSEHOLD);

        verify(notificationService, never()).notify(any());
    }

    @Test
    void does_not_fire_when_radius_set_but_market_has_no_coordinates() {
        var home = owner(new BigDecimal("-30.0331"), new BigDecimal("-51.2300"));
        var alert = alert(new BigDecimal("6.00"), 5.0, null, home);
        when(alertRepository.findActiveByProductIdInFetchUser(any())).thenReturn(List.of(alert));
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of()); // unknown market

        service.evaluate(List.of(observation(new BigDecimal("5.00"))), CONTRIBUTOR_HOUSEHOLD);

        verify(notificationService, never()).notify(any());
    }

    @Test
    void no_alerts_means_no_lookup_side_effects() {
        when(alertRepository.findActiveByProductIdInFetchUser(any())).thenReturn(List.of());

        service.evaluate(List.of(observation(new BigDecimal("5.00"))), CONTRIBUTOR_HOUSEHOLD);

        verify(notificationService, never()).notify(any());
        verify(marketLocationService, never()).findByCnpjs(any());
    }

    @Test
    void empty_observations_is_a_noop() {
        service.evaluate(List.of(), CONTRIBUTOR_HOUSEHOLD);
        verify(alertRepository, never()).findActiveByProductIdInFetchUser(any());
    }

    // ---- CRUD ----

    @Test
    void create_persists_new_rule() {
        var user = owner(null, null);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(alertRepository.findByUserIdAndProductId(user.getId(), PRODUCT_ID)).thenReturn(Optional.empty());
        when(alertRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.create(user, new CreatePriceAlertRequest(PRODUCT_ID, new BigDecimal("6.00"), 5.0, null));

        assertEquals(new BigDecimal("6.00"), response.thresholdPrice());
        assertEquals(true, response.active());
    }

    @Test
    void create_unknown_product_throws() {
        var user = owner(null, null);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThrows(ProductNotFoundException.class, () ->
                service.create(user, new CreatePriceAlertRequest(PRODUCT_ID, new BigDecimal("6.00"), null, null)));
    }

    @Test
    void delete_unknown_alert_throws() {
        var user = owner(null, null);
        var id = UUID.randomUUID();
        when(alertRepository.findByIdAndUserId(id, user.getId())).thenReturn(Optional.empty());

        assertThrows(PriceAlertNotFoundException.class, () -> service.delete(user, id));
    }
}
