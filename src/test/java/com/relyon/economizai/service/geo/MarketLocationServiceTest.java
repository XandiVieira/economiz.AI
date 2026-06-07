package com.relyon.economizai.service.geo;

import com.relyon.economizai.model.MarketLocation;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.enums.CategorizationSource;
import com.relyon.economizai.model.enums.MerchantSegment;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.repository.MarketLocationRepository;
import com.relyon.economizai.repository.ProductRepository;
import com.relyon.economizai.service.geo.NominatimGeocoder.GeocodeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketLocationServiceTest {

    @Mock private MarketLocationRepository repository;
    @Mock private NominatimGeocoder geocoder;
    @Mock private CnpjActivityClient cnpjActivityClient;
    @Mock private ProductRepository productRepository;

    private MarketLocationService service;

    @BeforeEach
    void setUp() {
        service = new MarketLocationService(repository, geocoder, cnpjActivityClient, productRepository);
    }

    // ---------- registerMarketFromReceipt ----------

    @Test
    void registerMarketFromReceipt_skipsWhenCnpjNull() {
        var receipt = receipt(null, "Mercado", "Rua A");

        service.registerMarketFromReceipt(receipt);

        verify(repository, never()).findByCnpj(any());
        verify(repository, never()).save(any());
    }

    @Test
    void registerMarketFromReceipt_skipsWhenAlreadyRegistered() {
        var receipt = receipt("11111111000111", "Mercado", "Rua A");
        when(repository.findByCnpj(eq("11111111000111")))
                .thenReturn(Optional.of(new MarketLocation()));

        service.registerMarketFromReceipt(receipt);

        verify(repository, never()).save(any());
    }

    @Test
    void registerMarketFromReceipt_savesNewLocationWithCnpjRoot() {
        var receipt = receipt("11111111000199", "Zaffari Centro", "Av Brasil, 100");
        when(repository.findByCnpj(eq("11111111000199"))).thenReturn(Optional.empty());

        service.registerMarketFromReceipt(receipt);

        var saved = ArgumentCaptor.forClass(MarketLocation.class);
        verify(repository).save(saved.capture());
        var location = saved.getValue();
        assertThat(location.getCnpj()).isEqualTo("11111111000199");
        assertThat(location.getCnpjRoot()).isEqualTo("11111111");
        assertThat(location.getName()).isEqualTo("Zaffari Centro");
        assertThat(location.getAddress()).isEqualTo("Av Brasil, 100");
    }

    // ---------- geocodeOne ----------

    @Test
    void geocodeOne_onHit_setsCoordinatesAndClearsFailure() {
        var market = MarketLocation.builder()
                .cnpj("11111111000111").cnpjRoot("11111111")
                .address("Av Brasil, 100").geocodeAttempts(0).build();
        market.setGeocodeFailedAt(LocalDateTime.now());
        when(geocoder.geocode(any())).thenReturn(Optional.of(
                new GeocodeResult(new BigDecimal("-30.05"), new BigDecimal("-51.22"), "Porto Alegre", "RS")));

        service.geocodeOne(market);

        assertThat(market.getGeocodeAttempts()).isEqualTo(1);
        assertThat(market.getLatitude()).isEqualByComparingTo("-30.05");
        assertThat(market.getLongitude()).isEqualByComparingTo("-51.22");
        assertThat(market.getCity()).isEqualTo("Porto Alegre");
        assertThat(market.getState()).isEqualTo("RS");
        assertThat(market.getGeocodedAt()).isNotNull();
        assertThat(market.getGeocodeFailedAt()).isNull();
        verify(repository).save(market);
    }

    @Test
    void geocodeOne_onMiss_recordsFailureAndIncrementsAttempts() {
        var market = MarketLocation.builder()
                .cnpj("11111111000111").cnpjRoot("11111111")
                .address("Nowhere").geocodeAttempts(1).build();
        when(geocoder.geocode(any())).thenReturn(Optional.empty());

        service.geocodeOne(market);

        assertThat(market.getGeocodeAttempts()).isEqualTo(2);
        assertThat(market.getLatitude()).isNull();
        assertThat(market.getGeocodeFailedAt()).isNotNull();
        assertThat(market.getGeocodedAt()).isNull();
        verify(repository).save(market);
    }

    // ---------- geocodePending ----------

    @Test
    void geocodePending_noPending_doesNothing() {
        when(repository.findAllByLatitudeIsNullAndGeocodeAttemptsLessThan(anyInt()))
                .thenReturn(List.of());

        service.geocodePending();

        verify(geocoder, never()).geocode(any());
    }

    @Test
    void geocodePending_geocodesEachPendingMarket() {
        var marketOne = MarketLocation.builder().cnpj("1").cnpjRoot("11111111").address("A").build();
        var marketTwo = MarketLocation.builder().cnpj("2").cnpjRoot("22222222").address("B").build();
        when(repository.findAllByLatitudeIsNullAndGeocodeAttemptsLessThan(anyInt()))
                .thenReturn(List.of(marketOne, marketTwo));
        when(geocoder.geocode(any())).thenReturn(Optional.empty());

        service.geocodePending();

        verify(geocoder, times(2)).geocode(any());
        verify(repository, times(2)).save(any());
    }

    // ---------- buildGeocodeQuery (via geocodeOne, asserting the query passed) ----------

    @Test
    void buildGeocodeQuery_prefersAddress() {
        var market = MarketLocation.builder().cnpj("c").cnpjRoot("cccccccc")
                .address("Rua X").name("Nome").build();
        when(geocoder.geocode(eq("Rua X, Brasil"))).thenReturn(Optional.empty());

        service.geocodeOne(market);

        verify(geocoder).geocode(eq("Rua X, Brasil"));
    }

    @Test
    void buildGeocodeQuery_fallsBackToNameWhenAddressBlank() {
        var market = MarketLocation.builder().cnpj("c").cnpjRoot("cccccccc")
                .address("  ").name("Mercado Nome").build();
        when(geocoder.geocode(eq("Mercado Nome, Brasil"))).thenReturn(Optional.empty());

        service.geocodeOne(market);

        verify(geocoder).geocode(eq("Mercado Nome, Brasil"));
    }

    @Test
    void buildGeocodeQuery_fallsBackToCnpjWhenAddressAndNameMissing() {
        var market = MarketLocation.builder().cnpj("11111111000111").cnpjRoot("11111111")
                .address(null).name(null).build();
        when(geocoder.geocode(eq("CNPJ 11111111000111, Brasil"))).thenReturn(Optional.empty());

        service.geocodeOne(market);

        verify(geocoder).geocode(eq("CNPJ 11111111000111, Brasil"));
    }

    // ---------- classifyPendingSegments ----------

    @Test
    void classifyPendingSegments_returnsZeroSummaryWhenDisabled() {
        when(cnpjActivityClient.isEnabled()).thenReturn(false);

        var summary = service.classifyPendingSegments();

        assertThat(summary.attempted()).isZero();
        verify(repository, never()).findAllBySegmentAndSegmentAttemptsLessThan(any(), anyInt());
    }

    @Test
    void classifyPendingSegments_returnsZeroSummaryWhenNoPending() {
        when(cnpjActivityClient.isEnabled()).thenReturn(true);
        when(repository.findAllBySegmentAndSegmentAttemptsLessThan(eq(MerchantSegment.UNKNOWN), anyInt()))
                .thenReturn(List.of());

        var summary = service.classifyPendingSegments();

        assertThat(summary.attempted()).isZero();
        verify(cnpjActivityClient, never()).classify(any());
    }

    @Test
    void classifyPendingSegments_talliesEachSegment() {
        var pharmacyMarket = MarketLocation.builder().cnpj("p").cnpjRoot("pppppppp").build();
        var supermarketMarket = MarketLocation.builder().cnpj("s").cnpjRoot("ssssssss").build();
        var otherMarket = MarketLocation.builder().cnpj("o").cnpjRoot("oooooooo").build();
        var unknownMarket = MarketLocation.builder().cnpj("u").cnpjRoot("uuuuuuuu").build();
        when(cnpjActivityClient.isEnabled()).thenReturn(true);
        when(repository.findAllBySegmentAndSegmentAttemptsLessThan(eq(MerchantSegment.UNKNOWN), anyInt()))
                .thenReturn(List.of(pharmacyMarket, supermarketMarket, otherMarket, unknownMarket));
        when(cnpjActivityClient.classify(eq("p"))).thenReturn(MerchantSegment.PHARMACY);
        when(cnpjActivityClient.classify(eq("s"))).thenReturn(MerchantSegment.SUPERMARKET);
        when(cnpjActivityClient.classify(eq("o"))).thenReturn(MerchantSegment.OTHER);
        when(cnpjActivityClient.classify(eq("u"))).thenReturn(MerchantSegment.UNKNOWN);
        when(productRepository.findOtherCategoryProductsByMerchant(eq("p"))).thenReturn(List.of());

        var summary = service.classifyPendingSegments();

        assertThat(summary.attempted()).isEqualTo(4);
        assertThat(summary.pharmacy()).isEqualTo(1);
        assertThat(summary.supermarket()).isEqualTo(1);
        assertThat(summary.other()).isEqualTo(1);
        assertThat(summary.stillUnknown()).isEqualTo(1);
    }

    // ---------- classifySegmentOne ----------

    @Test
    void classifySegmentOne_setsSegmentWhenResolved() {
        var market = MarketLocation.builder().cnpj("c").cnpjRoot("cccccccc")
                .segment(MerchantSegment.UNKNOWN).segmentAttempts(0).build();
        when(cnpjActivityClient.classify(eq("c"))).thenReturn(MerchantSegment.SUPERMARKET);

        service.classifySegmentOne(market);

        assertThat(market.getSegment()).isEqualTo(MerchantSegment.SUPERMARKET);
        assertThat(market.getSegmentAttempts()).isEqualTo(1);
        assertThat(market.getSegmentClassifiedAt()).isNotNull();
        verify(repository).save(market);
        verify(productRepository, never()).findOtherCategoryProductsByMerchant(any());
    }

    @Test
    void classifySegmentOne_leavesUnknownWhenUnresolved() {
        var market = MarketLocation.builder().cnpj("c").cnpjRoot("cccccccc")
                .segment(MerchantSegment.UNKNOWN).segmentAttempts(2).build();
        when(cnpjActivityClient.classify(eq("c"))).thenReturn(MerchantSegment.UNKNOWN);

        service.classifySegmentOne(market);

        assertThat(market.getSegment()).isEqualTo(MerchantSegment.UNKNOWN);
        assertThat(market.getSegmentAttempts()).isEqualTo(3);
        assertThat(market.getSegmentClassifiedAt()).isNull();
        verify(repository).save(market);
    }

    @Test
    void classifySegmentOne_pharmacy_backfillsOtherProducts() {
        var market = MarketLocation.builder().cnpj("ph").cnpjRoot("pppppppp")
                .segment(MerchantSegment.UNKNOWN).segmentAttempts(0).build();
        var productOne = Product.builder().normalizedName("dipirona")
                .category(ProductCategory.OTHER).categorizationSource(CategorizationSource.NONE).build();
        var productTwo = Product.builder().normalizedName("paracetamol")
                .category(ProductCategory.OTHER).categorizationSource(CategorizationSource.NONE).build();
        when(cnpjActivityClient.classify(eq("ph"))).thenReturn(MerchantSegment.PHARMACY);
        when(productRepository.findOtherCategoryProductsByMerchant(eq("ph")))
                .thenReturn(List.of(productOne, productTwo));

        service.classifySegmentOne(market);

        assertThat(productOne.getCategory()).isEqualTo(ProductCategory.HEALTH);
        assertThat(productOne.getCategorizationSource()).isEqualTo(CategorizationSource.MERCHANT);
        assertThat(productTwo.getCategory()).isEqualTo(ProductCategory.HEALTH);
        verify(productRepository).saveAll(anyList());
    }

    @Test
    void classifySegmentOne_pharmacyWithNoProducts_doesNotSaveAll() {
        var market = MarketLocation.builder().cnpj("ph").cnpjRoot("pppppppp")
                .segment(MerchantSegment.UNKNOWN).segmentAttempts(0).build();
        when(cnpjActivityClient.classify(eq("ph"))).thenReturn(MerchantSegment.PHARMACY);
        when(productRepository.findOtherCategoryProductsByMerchant(eq("ph"))).thenReturn(List.of());

        service.classifySegmentOne(market);

        verify(productRepository, never()).saveAll(any());
    }

    // ---------- findByCnpjs ----------

    @Test
    void findByCnpjs_nullReturnsEmptyMap() {
        assertThat(service.findByCnpjs(null)).isEqualTo(Map.of());
        verifyNoInteractions(repository);
    }

    @Test
    void findByCnpjs_emptyReturnsEmptyMap() {
        assertThat(service.findByCnpjs(List.of())).isEqualTo(Map.of());
        verifyNoInteractions(repository);
    }

    @Test
    void findByCnpjs_indexesByCnpj() {
        var marketOne = MarketLocation.builder().cnpj("11111111000111").cnpjRoot("11111111").build();
        var marketTwo = MarketLocation.builder().cnpj("22222222000111").cnpjRoot("22222222").build();
        when(repository.findAllByCnpjIn(eq(List.of("11111111000111", "22222222000111"))))
                .thenReturn(List.of(marketOne, marketTwo));

        var byCnpj = service.findByCnpjs(List.of("11111111000111", "22222222000111"));

        assertThat(byCnpj).hasSize(2);
        assertThat(byCnpj.get("11111111000111")).isSameAs(marketOne);
        assertThat(byCnpj.get("22222222000111")).isSameAs(marketTwo);
    }

    @Test
    void scheduledSegmentClassification_delegatesToClassifyPending() {
        when(cnpjActivityClient.isEnabled()).thenReturn(false);

        service.scheduledSegmentClassification();

        verify(cnpjActivityClient).isEnabled();
    }

    private Receipt receipt(String cnpj, String name, String address) {
        var receipt = new Receipt();
        receipt.setCnpjEmitente(cnpj);
        receipt.setMarketName(name);
        receipt.setMarketAddress(address);
        return receipt;
    }
}
