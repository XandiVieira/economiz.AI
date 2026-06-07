package com.relyon.economizai.service.alerts;

import com.relyon.economizai.dto.request.CreatePriceAlertRequest;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.PriceAlert;
import com.relyon.economizai.model.PriceObservation;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.User;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage beyond {@link PriceAlertServiceTest}: the create-update path,
 * list/delete happy paths, the per-rule dedupe (already-fired) skip, the
 * no-alerts-for-product continue, and the null marketName notify fallback.
 */
@ExtendWith(MockitoExtension.class)
class PriceAlertServiceCoverageTest {

    @Mock private PriceAlertRepository alertRepository;
    @Mock private ProductRepository productRepository;
    @Mock private MarketLocationService marketLocationService;
    @Mock private NotificationService notificationService;
    @InjectMocks private PriceAlertService service;

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID OTHER_PRODUCT_ID = UUID.randomUUID();
    private static final UUID CONTRIBUTOR_HOUSEHOLD = UUID.randomUUID();
    private static final String CNPJ = "12345678000199";

    private Product product(UUID id, String name) {
        return Product.builder().id(id).normalizedName(name).build();
    }

    private User owner() {
        return User.builder().id(UUID.randomUUID()).email("owner@example.com")
                .household(Household.builder().id(UUID.randomUUID()).build())
                .build();
    }

    private PriceAlert alert(UUID alertId, BigDecimal threshold, Product product) {
        return PriceAlert.builder().id(alertId)
                .user(owner()).product(product)
                .thresholdPrice(threshold).radiusKm(null)
                .active(true).build();
    }

    private PriceObservation observation(Product product, BigDecimal unitPrice, String marketName) {
        return PriceObservation.builder()
                .product(product).marketCnpj(CNPJ).marketCnpjRoot("12345678")
                .marketName(marketName).unitPrice(unitPrice).quantity(BigDecimal.ONE)
                .observedAt(LocalDateTime.now()).build();
    }

    // ---- create (update path) ----

    @Test
    void create_updatesExistingRule_inPlace() {
        var user = owner();
        var existingProduct = product(PRODUCT_ID, "Leite Italac 1L");
        var existing = PriceAlert.builder().id(UUID.randomUUID())
                .user(user).product(existingProduct)
                .thresholdPrice(new BigDecimal("9.99")).radiusKm(20.0)
                .active(true).build();

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(existingProduct));
        when(alertRepository.findByUserIdAndProductId(user.getId(), PRODUCT_ID)).thenReturn(Optional.of(existing));
        when(alertRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(user, new CreatePriceAlertRequest(PRODUCT_ID, new BigDecimal("6.00"), 3.0, false));

        // existing instance mutated, not replaced
        assertEquals(new BigDecimal("6.00"), existing.getThresholdPrice());
        assertEquals(3.0, existing.getRadiusKm());
        assertFalse(existing.isActive());
        assertEquals(new BigDecimal("6.00"), response.thresholdPrice());
        assertFalse(response.active());
    }

    @Test
    void create_defaultsActiveTrueWhenNullInRequest() {
        var user = owner();
        var product = product(PRODUCT_ID, "Leite Italac 1L");
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(alertRepository.findByUserIdAndProductId(user.getId(), PRODUCT_ID)).thenReturn(Optional.empty());
        when(alertRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(user, new CreatePriceAlertRequest(PRODUCT_ID, new BigDecimal("6.00"), null, null));

        assertEquals(true, response.active());
    }

    // ---- list ----

    @Test
    void list_mapsRepositoryResults() {
        var user = owner();
        when(alertRepository.findAllByUserIdFetchProduct(user.getId()))
                .thenReturn(List.of(alert(UUID.randomUUID(), new BigDecimal("4.00"), product(PRODUCT_ID, "Arroz"))));

        var responses = service.list(user);

        assertEquals(1, responses.size());
        assertEquals(new BigDecimal("4.00"), responses.get(0).thresholdPrice());
    }

    // ---- delete ----

    @Test
    void delete_removesWhenFound() {
        var user = owner();
        var alertId = UUID.randomUUID();
        var existing = alert(alertId, new BigDecimal("4.00"), product(PRODUCT_ID, "Arroz"));
        when(alertRepository.findByIdAndUserId(alertId, user.getId())).thenReturn(Optional.of(existing));

        service.delete(user, alertId);

        verify(alertRepository).delete(existing);
    }

    // ---- evaluate ----

    @Test
    void evaluate_firesAlertOnceAcrossMultipleMatchingObservations() {
        var matchedProduct = product(PRODUCT_ID, "Leite Italac 1L");
        var alert = alert(UUID.randomUUID(), new BigDecimal("6.00"), matchedProduct);
        when(alertRepository.findActiveByProductIdInFetchUser(any())).thenReturn(List.of(alert));
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());

        // two qualifying observations for the same product → still only one notification
        var observations = List.of(
                observation(matchedProduct, new BigDecimal("5.00"), "Zaffari"),
                observation(matchedProduct, new BigDecimal("4.50"), "Nacional"));

        service.evaluate(observations, CONTRIBUTOR_HOUSEHOLD);

        verify(notificationService, times(1)).notify(any());
    }

    @Test
    void evaluate_skipsObservationsWhoseProductHasNoAlerts() {
        var alertedProduct = product(PRODUCT_ID, "Leite Italac 1L");
        var unrelatedProduct = product(OTHER_PRODUCT_ID, "Café");
        var alert = alert(UUID.randomUUID(), new BigDecimal("6.00"), alertedProduct);
        when(alertRepository.findActiveByProductIdInFetchUser(any())).thenReturn(List.of(alert));
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());

        // observation is for a product with no alert → the inner continue path
        service.evaluate(List.of(observation(unrelatedProduct, new BigDecimal("1.00"), "Zaffari")), CONTRIBUTOR_HOUSEHOLD);

        verify(notificationService, never()).notify(any());
    }

    @Test
    void evaluate_notifyUsesFallbackWhenMarketNameNull() {
        var matchedProduct = product(PRODUCT_ID, "Leite Italac 1L");
        var alert = alert(UUID.randomUUID(), new BigDecimal("6.00"), matchedProduct);
        when(alertRepository.findActiveByProductIdInFetchUser(any())).thenReturn(List.of(alert));
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());

        service.evaluate(List.of(observation(matchedProduct, new BigDecimal("5.00"), null)), CONTRIBUTOR_HOUSEHOLD);

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        var payload = captor.getValue();
        assertEquals("", payload.extras().get("marketName"));
        assertEquals(true, payload.body().contains("um mercado próximo"));
    }
}
