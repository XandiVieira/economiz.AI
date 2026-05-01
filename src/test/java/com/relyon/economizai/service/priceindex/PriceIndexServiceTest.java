package com.relyon.economizai.service.priceindex;

import com.relyon.economizai.config.CollaborativeProperties;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.MarketLocation;
import com.relyon.economizai.model.PriceObservation;
import com.relyon.economizai.model.PriceObservationAudit;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.PriceObservationAuditRepository;
import com.relyon.economizai.repository.PriceObservationRepository;
import com.relyon.economizai.service.geo.MarketLocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceIndexServiceTest {

    @Mock private PriceObservationRepository observationRepository;
    @Mock private PriceObservationAuditRepository auditRepository;
    @Mock private MarketLocationService marketLocationService;

    private CollaborativeProperties properties;
    private PriceIndexService service;

    @BeforeEach
    void setUp() {
        properties = new CollaborativeProperties();
        service = new PriceIndexService(observationRepository, auditRepository, properties, marketLocationService);
    }

    private Receipt buildConfirmedReceipt(boolean optIn, ReceiptItem... items) {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        var user = User.builder().id(UUID.randomUUID()).email("u@e").household(household)
                .contributionOptIn(optIn).build();
        var receipt = Receipt.builder()
                .id(UUID.randomUUID())
                .user(user)
                .household(household)
                .cnpjEmitente("93015006005182")
                .marketName("Mercado X")
                .issuedAt(LocalDateTime.now())
                .build();
        for (var item : items) receipt.addItem(item);
        return receipt;
    }

    private ReceiptItem itemWithProduct(Product product, BigDecimal unitPrice) {
        return ReceiptItem.builder()
                .id(UUID.randomUUID())
                .lineNumber(1)
                .rawDescription(product.getNormalizedName())
                .quantity(BigDecimal.ONE)
                .unitPrice(unitPrice)
                .totalPrice(unitPrice)
                .product(product)
                .build();
    }

    private Product product() {
        return Product.builder().id(UUID.randomUUID()).normalizedName("Arroz").build();
    }

    @Test
    void recordContributions_skipsWhenUserOptedOut() {
        var receipt = buildConfirmedReceipt(false, itemWithProduct(product(), new BigDecimal("10")));

        var written = service.recordContributions(receipt);

        assertEquals(0, written);
        verify(observationRepository, never()).save(any());
        verify(auditRepository, never()).save(any());
    }

    @Test
    void recordContributions_skipsWhenMasterSwitchOff() {
        properties.getCollaborative().setEnabled(false);
        var receipt = buildConfirmedReceipt(true, itemWithProduct(product(), new BigDecimal("10")));

        var written = service.recordContributions(receipt);

        assertEquals(0, written);
        verify(observationRepository, never()).save(any());
    }

    @Test
    void recordContributions_writesOneObservationAndAuditPerLinkedItem() {
        var p1 = product();
        var p2 = Product.builder().id(UUID.randomUUID()).normalizedName("Leite").build();
        var item1 = itemWithProduct(p1, new BigDecimal("10"));
        var item2 = itemWithProduct(p2, new BigDecimal("5"));
        var unlinked = ReceiptItem.builder()
                .id(UUID.randomUUID())
                .lineNumber(3)
                .rawDescription("UNKNOWN")
                .quantity(BigDecimal.ONE)
                .unitPrice(BigDecimal.ONE)
                .totalPrice(BigDecimal.ONE)
                .build(); // no product linked → must be skipped
        var receipt = buildConfirmedReceipt(true, item1, item2, unlinked);

        when(observationRepository.save(any(PriceObservation.class))).thenAnswer(inv -> {
            var obs = inv.<PriceObservation>getArgument(0);
            obs.setId(UUID.randomUUID());
            return obs;
        });

        var written = service.recordContributions(receipt);

        assertEquals(2, written);
        verify(observationRepository, times(2)).save(any(PriceObservation.class));
        verify(auditRepository, times(2)).save(any(PriceObservationAudit.class));
    }

    @Test
    void recordContributions_setsCnpjRoot() {
        var receipt = buildConfirmedReceipt(true, itemWithProduct(product(), new BigDecimal("10")));
        when(observationRepository.save(any(PriceObservation.class))).thenAnswer(inv -> {
            var obs = inv.<PriceObservation>getArgument(0);
            assertEquals("93015006", obs.getMarketCnpjRoot());
            obs.setId(UUID.randomUUID());
            return obs;
        });

        service.recordContributions(receipt);

        verify(observationRepository).save(any(PriceObservation.class));
    }

    @Test
    void referencePrice_returnsEmptyWhenInsufficientObservations() {
        var productId = UUID.randomUUID();
        when(observationRepository.findRecentByProductAndMarket(eq(productId), eq("123"), any()))
                .thenReturn(List.of()); // 0 observations

        var ref = service.referencePrice(productId, "123");

        assertFalse(ref.hasData());
    }

    @Test
    void referencePrice_returnsEmptyWhenKAnonBlocks() {
        var productId = UUID.randomUUID();
        // 6 observations from same household → enough samples but only 1 household → blocked
        var observations = List.of(
                obs(productId, new BigDecimal("10")), obs(productId, new BigDecimal("11")),
                obs(productId, new BigDecimal("12")), obs(productId, new BigDecimal("10")),
                obs(productId, new BigDecimal("11")), obs(productId, new BigDecimal("10"))
        );
        when(observationRepository.findRecentByProductAndMarket(eq(productId), eq("123"), any()))
                .thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductMarket(eq(productId), eq("123"), any()))
                .thenReturn(1L); // only 1 distinct household

        var ref = service.referencePrice(productId, "123");

        assertFalse(ref.hasData(), "k-anon must block when fewer than min households");
    }

    @Test
    void referencePrice_returnsDataWhenAllThresholdsMet() {
        var productId = UUID.randomUUID();
        var observations = List.of(
                obs(productId, new BigDecimal("10")), obs(productId, new BigDecimal("11")),
                obs(productId, new BigDecimal("12")), obs(productId, new BigDecimal("9")),
                obs(productId, new BigDecimal("13"))
        );
        when(observationRepository.findRecentByProductAndMarket(eq(productId), eq("123"), any()))
                .thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductMarket(eq(productId), eq("123"), any()))
                .thenReturn(3L);

        var ref = service.referencePrice(productId, "123");

        assertTrue(ref.hasData());
        assertEquals(0, ref.medianPrice().compareTo(new BigDecimal("11")));
        assertEquals(5, ref.sampleCount());
        assertEquals(3, ref.distinctHouseholds());
    }

    @Test
    void bestMarkets_filtersOutMarketsBelowThresholds() {
        var productId = UUID.randomUUID();
        // market A: 5 obs from 3 households (passes) median 10
        // market B: 5 obs from 1 household (k-anon blocks) median 8
        var observations = new ArrayList<PriceObservation>();
        for (var i = 0; i < 5; i++) observations.add(obsAt(productId, "AAAAAAAA000111", "A", new BigDecimal("10")));
        for (var i = 0; i < 5; i++) observations.add(obsAt(productId, "BBBBBBBB000111", "B", new BigDecimal("8")));

        when(observationRepository.findRecentByProduct(eq(productId), any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductMarket(eq(productId), eq("AAAAAAAA000111"), any())).thenReturn(3L);
        when(auditRepository.countDistinctHouseholdsForProductMarket(eq(productId), eq("BBBBBBBB000111"), any())).thenReturn(1L);
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());

        var rows = service.bestMarkets(productId, 10, null, null, null, Set.of());

        assertEquals(1, rows.size(), "k-anon must hide market B");
        assertEquals("AAAAAAAA000111", rows.get(0).cnpj());
    }

    @Test
    void bestMarkets_watchedMarketBypassesRadiusFilter() {
        var productId = UUID.randomUUID();
        var observations = new ArrayList<PriceObservation>();
        // distant market — would be excluded by 1km radius — but is in the watchlist
        for (var i = 0; i < 5; i++) observations.add(obsAt(productId, "DDDDDDDD000111", "Distante", new BigDecimal("9")));

        when(observationRepository.findRecentByProduct(eq(productId), any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductMarket(eq(productId), eq("DDDDDDDD000111"), any()))
                .thenReturn(3L);
        var loc = MarketLocation.builder()
                .cnpj("DDDDDDDD000111").cnpjRoot("DDDDDDDD")
                .latitude(new BigDecimal("-30.0500000")).longitude(new BigDecimal("-51.2200000")) // ~10km away
                .build();
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of("DDDDDDDD000111", loc));

        var rows = service.bestMarkets(productId, 10,
                new BigDecimal("-30.0000000"), new BigDecimal("-51.2000000"),
                1.0, // 1km radius — would normally exclude
                Set.of("DDDDDDDD000111"));

        assertEquals(1, rows.size(), "watched market must bypass radius filter");
        assertTrue(rows.get(0).watching());
    }

    private PriceObservation obs(UUID productId, BigDecimal price) {
        return obsAt(productId, "93015006005182", "Mercado X", price);
    }

    private PriceObservation obsAt(UUID productId, String cnpj, String name, BigDecimal price) {
        return PriceObservation.builder()
                .id(UUID.randomUUID())
                .product(Product.builder().id(productId).normalizedName("X").build())
                .marketCnpj(cnpj)
                .marketCnpjRoot(cnpj.substring(0, 8))
                .marketName(name)
                .unitPrice(price)
                .quantity(BigDecimal.ONE)
                .observedAt(LocalDateTime.now())
                .build();
    }
}
