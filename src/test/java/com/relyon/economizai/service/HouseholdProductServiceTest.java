package com.relyon.economizai.service;

import com.relyon.economizai.dto.response.ProductMarketPriceResponse.PriceType;
import com.relyon.economizai.exception.ProductNotFoundException;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.MarketLocation;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.repository.ProductRepository;
import com.relyon.economizai.repository.ReceiptItemRepository;
import com.relyon.economizai.service.geo.MarketLocationService;
import com.relyon.economizai.service.geo.MarketNameService;
import com.relyon.economizai.service.geo.WatchedMarketService;
import com.relyon.economizai.service.priceindex.PriceIndexService;
import com.relyon.economizai.service.priceindex.PriceIndexService.MarketPriceRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HouseholdProductServiceTest {

    private static final UUID HOUSEHOLD_ID = UUID.randomUUID();
    private static final String CNPJ_ZAFFARI = "12345678000199";
    private static final String CNPJ_NACIONAL = "99887766000155";

    @Mock private ProductRepository productRepository;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private PriceIndexService priceIndexService;
    @Mock private WatchedMarketService watchedMarketService;
    @Mock private MarketLocationService marketLocationService;
    @Mock private MarketNameService marketNameService;

    @InjectMocks private HouseholdProductService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(UUID.randomUUID())
                .email("u@e")
                .household(Household.builder().id(HOUSEHOLD_ID).build())
                .homeLatitude(new BigDecimal("-30"))
                .homeLongitude(new BigDecimal("-51"))
                .build();
        lenient().when(marketNameService.resolveNames(any(), any())).thenReturn(Map.of());
        lenient().when(marketNameService.applyOverride(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }

    // ---------------------------------------------------------- listHouseholdProducts

    @Test
    void listHouseholdProducts_aggregatesCountAndMostRecentPurchase() {
        var product = product("Leite");
        var older = item(product, "5.00", CNPJ_ZAFFARI, "Zaffari",
                LocalDateTime.of(2026, 1, 1, 10, 0));
        var newer = item(product, "4.50", CNPJ_NACIONAL, "Nacional",
                LocalDateTime.of(2026, 2, 1, 10, 0));
        // Repository returns oldest -> newest; the last one seen wins as most-recent.
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(older, newer));

        var result = service.listHouseholdProducts(user);

        assertEquals(1, result.size());
        var row = result.get(0);
        assertEquals(2, row.timesBought());
        assertEquals(CNPJ_NACIONAL, row.lastMarketCnpj());
        assertEquals("Nacional", row.lastMarketName());
        assertEquals("Nacional", row.lastMarketFriendlyName());
        assertEquals(0, row.lastUnitPrice().compareTo(new BigDecimal("4.50")));
    }

    @Test
    void listHouseholdProducts_resolvesFriendlyNameFromOverride() {
        var product = product("Leite");
        var item = item(product, "5.00", CNPJ_ZAFFARI, "Zaffari",
                LocalDateTime.of(2026, 1, 1, 10, 0));
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(item));
        when(marketNameService.resolveNames(eq(HOUSEHOLD_ID), any()))
                .thenReturn(Map.of(CNPJ_ZAFFARI, "Zaffari de casa"));

        var result = service.listHouseholdProducts(user);

        var row = result.get(0);
        assertEquals("Zaffari", row.lastMarketName());
        assertEquals("Zaffari de casa", row.lastMarketFriendlyName());
    }

    @Test
    void listHouseholdProducts_emptyWhenNoHistory() {
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID)).thenReturn(List.of());

        assertEquals(List.of(), service.listHouseholdProducts(user));
    }

    // ---------------------------------------------------------- productMarkets

    @Test
    void productMarkets_unknownProduct_throws() {
        var productId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        assertThrows(ProductNotFoundException.class,
                () -> service.productMarkets(user, productId, false, null));
    }

    @Test
    void productMarkets_mergesOwnAndCommunitySortedByPriceAsc() {
        var product = product("Leite");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        // Own last paid at Zaffari = 5.00
        var ownItem = item(product, "5.00", CNPJ_ZAFFARI, "Zaffari",
                LocalDateTime.of(2026, 2, 1, 10, 0));
        when(receiptItemRepository.findHouseholdHistoryForProduct(product.getId(), HOUSEHOLD_ID))
                .thenReturn(List.of(ownItem));
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of(CNPJ_ZAFFARI, location(CNPJ_ZAFFARI, "Zaffari")));
        when(watchedMarketService.watchedCnpjs(user)).thenReturn(Set.of());
        // Community median at Nacional = 4.00 (cheaper), and one row for Zaffari that must be ignored (own wins).
        when(priceIndexService.bestMarkets(eq(product.getId()), anyInt(), any(), any(), isNull(), any()))
                .thenReturn(List.of(
                        new MarketPriceRow(CNPJ_NACIONAL, "99887766", "Nacional",
                                new BigDecimal("4.00"), new BigDecimal("3.50"), 5, 3L, 2.0, false),
                        new MarketPriceRow(CNPJ_ZAFFARI, "12345678", "Zaffari Community",
                                new BigDecimal("6.00"), new BigDecimal("5.50"), 4, 3L, 1.0, false)));

        var result = service.productMarkets(user, product.getId(), false, null);

        assertEquals(2, result.size());
        // Sorted by price asc: Nacional (4.00) then Zaffari own (5.00).
        assertEquals(CNPJ_NACIONAL, result.get(0).cnpj());
        assertEquals(PriceType.COMMUNITY_MEDIAN, result.get(0).priceType());
        assertEquals(0, result.get(0).price().compareTo(new BigDecimal("4.00")));
        assertEquals(CNPJ_ZAFFARI, result.get(1).cnpj());
        assertEquals(PriceType.OWN_LAST, result.get(1).priceType());
        assertEquals(0, result.get(1).price().compareTo(new BigDecimal("5.00")));
    }

    @Test
    void productMarkets_communityMedianRoundedToTwoDecimals() {
        var product = product("Leite");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(receiptItemRepository.findHouseholdHistoryForProduct(product.getId(), HOUSEHOLD_ID))
                .thenReturn(List.of());
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());
        when(watchedMarketService.watchedCnpjs(user)).thenReturn(Set.of());
        // A scale-4 median (5.3350) must be rounded HALF_UP to 5.34 in the response.
        when(priceIndexService.bestMarkets(eq(product.getId()), anyInt(), any(), any(), isNull(), any()))
                .thenReturn(List.of(new MarketPriceRow(CNPJ_NACIONAL, "99887766", "Nacional",
                        new BigDecimal("5.3350"), new BigDecimal("3.50"), 5, 3L, 2.0, false)));

        var result = service.productMarkets(user, product.getId(), false, null);

        assertEquals(1, result.size());
        assertEquals(new BigDecimal("5.34"), result.get(0).price());
        assertEquals(2, result.get(0).price().scale());
    }

    @Test
    void productMarkets_friendlyNameReflectsOverride() {
        var product = product("Leite");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        var ownItem = item(product, "5.00", CNPJ_ZAFFARI, "Zaffari",
                LocalDateTime.of(2026, 2, 1, 10, 0));
        when(receiptItemRepository.findHouseholdHistoryForProduct(product.getId(), HOUSEHOLD_ID))
                .thenReturn(List.of(ownItem));
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());
        when(watchedMarketService.watchedCnpjs(user)).thenReturn(Set.of());
        when(priceIndexService.bestMarkets(eq(product.getId()), anyInt(), any(), any(), isNull(), any()))
                .thenReturn(List.of());
        when(marketNameService.resolveNames(eq(HOUSEHOLD_ID), any()))
                .thenReturn(Map.of(CNPJ_ZAFFARI, "Zaffari de casa"));
        when(marketNameService.applyOverride(any(), eq(CNPJ_ZAFFARI), any())).thenReturn("Zaffari de casa");

        var result = service.productMarkets(user, product.getId(), false, null);

        assertEquals(1, result.size());
        assertEquals("Zaffari", result.get(0).marketName());
        assertEquals("Zaffari de casa", result.get(0).friendlyName());
    }

    @Test
    void productMarkets_includeNearby_passesRadiusToBestMarkets() {
        var product = product("Leite");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(receiptItemRepository.findHouseholdHistoryForProduct(product.getId(), HOUSEHOLD_ID))
                .thenReturn(List.of());
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());
        when(watchedMarketService.watchedCnpjs(user)).thenReturn(Set.of());
        when(priceIndexService.bestMarkets(eq(product.getId()), anyInt(), any(), any(), eq(7.5), any()))
                .thenReturn(List.of());

        service.productMarkets(user, product.getId(), true, 7.5);

        verify(priceIndexService).bestMarkets(eq(product.getId()), anyInt(), any(), any(), eq(7.5), any());
    }

    // ---------------------------------------------------------- helpers

    private Product product(String name) {
        return Product.builder().id(UUID.randomUUID()).normalizedName(name)
                .category(ProductCategory.MEAT_DAIRY).build();
    }

    private ReceiptItem item(Product product, String unitPrice, String cnpj, String marketName, LocalDateTime issuedAt) {
        return ReceiptItem.builder()
                .product(product)
                .unitPrice(new BigDecimal(unitPrice))
                .receipt(Receipt.builder()
                        .cnpjEmitente(cnpj)
                        .marketName(marketName)
                        .issuedAt(issuedAt)
                        .status(ReceiptStatus.CONFIRMED)
                        .build())
                .build();
    }

    private MarketLocation location(String cnpj, String name) {
        return MarketLocation.builder().cnpj(cnpj).name(name).build();
    }
}
