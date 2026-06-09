package com.relyon.economizai.service;

import com.relyon.economizai.dto.request.SubmitReceiptRequest;
import com.relyon.economizai.dto.request.UpdateReceiptItemRequest;
import com.relyon.economizai.exception.PaywallException;
import com.relyon.economizai.exception.ReceiptAlreadyIngestedException;
import com.relyon.economizai.exception.ReceiptItemNotFoundException;
import com.relyon.economizai.exception.ReceiptNotEditableException;
import com.relyon.economizai.exception.ReceiptNotFoundException;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import com.relyon.economizai.repository.ReceiptItemRepository;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.service.HouseholdProductAliasService;
import com.relyon.economizai.service.cache.HouseholdCacheGen;
import com.relyon.economizai.service.canonicalization.CanonicalizationService;
import com.relyon.economizai.service.geo.MarketLocationService;
import com.relyon.economizai.service.geo.MarketNameService;
import com.relyon.economizai.service.priceindex.PriceIndexService;
import com.relyon.economizai.service.priceindex.PromoDetector;
import com.relyon.economizai.service.sefaz.ParsedReceipt;
import com.relyon.economizai.service.sefaz.ParsedReceiptItem;
import com.relyon.economizai.service.sefaz.SefazIngestionService;
import com.relyon.economizai.service.subscription.SubscriptionGateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceiptServiceTest {

    private static final String CHAVE_RS = "43260412345678000190650010000123451123456780";

    @Mock private ReceiptRepository receiptRepository;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private SefazIngestionService sefazIngestionService;
    @Mock private CanonicalizationService canonicalizationService;
    @Mock private PriceIndexService priceIndexService;
    @Mock private PromoDetector promoDetector;
    @Mock private MarketLocationService marketLocationService;
    @Mock private HouseholdProductAliasService householdProductAliasService;
    @Mock private HouseholdProductCategoryOverrideService categoryOverrideService;
    @Mock private HouseholdCacheGen householdCacheGen;
    @Mock private MarketNameService marketNameService;
    @Mock private SubscriptionGateService subscriptionGate;

    @InjectMocks private ReceiptService receiptService;

    @BeforeEach
    void stubMarketNames() {
        lenient().when(marketNameService.resolve(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        lenient().when(marketNameService.resolveNames(any(), any())).thenReturn(Map.of());
        lenient().when(marketNameService.applyOverride(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        // Default: PRO (unlimited) so the monthly cap is bypassed in existing tests.
        lenient().when(subscriptionGate.monthlyReceiptLimit(any())).thenReturn(Integer.MAX_VALUE);
    }

    @Test
    void updateItemCategory_unlinkedItem_throws() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.CONFIRMED); // item has no product
        var item = receipt.getItems().get(0);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));

        assertThrows(ReceiptNotEditableException.class,
                () -> receiptService.updateItemCategory(user, receipt.getId(), item.getId(), ProductCategory.GROCERIES));
        verify(categoryOverrideService, never()).setOverride(any(), any(), any());
    }

    @Test
    void updateItemCategory_recordsHouseholdOverrideAndAppliesIt() {
        var user = buildUser();
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("ARROZ TIO J 5KG")
                .category(ProductCategory.OTHER).build();
        var receipt = persistedReceipt(user, ReceiptStatus.CONFIRMED);
        var item = receipt.getItems().get(0);
        item.setProduct(product);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));
        when(categoryOverrideService.overridesByProduct(eq(user.getHousehold().getId()), any()))
                .thenReturn(Map.of(product.getId(), "GROCERIES"));

        var response = receiptService.updateItemCategory(user, receipt.getId(), item.getId(), ProductCategory.GROCERIES);

        verify(categoryOverrideService).setOverride(user, product, ProductCategory.GROCERIES);
        assertEquals("GROCERIES", response.items().get(0).category(), "household override shown, global stays OTHER");
        assertEquals(ProductCategory.OTHER, product.getCategory(), "global product untouched");
    }

    private User buildUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        var user = User.builder()
                .id(UUID.randomUUID())
                .name("John")
                .email("john@test.com")
                .password("encoded")
                .household(household)
                .build();
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return user;
    }

    private ParsedReceipt sampleParsed() {
        return ParsedReceipt.builder()
                .chaveAcesso(CHAVE_RS)
                .cnpjEmitente("12345678000190")
                .marketName("Mercado X")
                .marketAddress("Rua Y, 123")
                .issuedAt(LocalDateTime.of(2026, Month.APRIL, 15, 18, 0))
                .totalAmount(new BigDecimal("57.80"))
                .sourceUrl(null)
                .rawHtml("<html/>")
                .items(List.of(ParsedReceiptItem.builder()
                        .lineNumber(1)
                        .rawDescription("ARROZ TIO J 5KG")
                        .ean("7891234567890")
                        .quantity(new BigDecimal("2"))
                        .unit("UN")
                        .unitPrice(new BigDecimal("28.90"))
                        .totalPrice(new BigDecimal("57.80"))
                        .build()))
                .build();
    }

    private Receipt persistedReceipt(User user, ReceiptStatus status) {
        var receipt = Receipt.builder()
                .id(UUID.randomUUID())
                .user(user)
                .household(user.getHousehold())
                .chaveAcesso(CHAVE_RS)
                .uf(UnidadeFederativa.RS)
                .qrPayload(CHAVE_RS)
                .status(status)
                .build();
        receipt.addItem(ReceiptItem.builder()
                .id(UUID.randomUUID())
                .lineNumber(1)
                .rawDescription("ARROZ TIO J 5KG")
                .quantity(new BigDecimal("2"))
                .unit("UN")
                .unitPrice(new BigDecimal("28.90"))
                .totalPrice(new BigDecimal("57.80"))
                .build());
        return receipt;
    }

    @Test
    void submit_persistsParsedReceiptInPendingStatus() {
        var user = buildUser();
        when(receiptRepository.findByHouseholdIdAndChaveAcesso(any(), eq(CHAVE_RS))).thenReturn(Optional.empty());
        var fetched = new SefazIngestionService.FetchedDocument(null, "<html/>", CHAVE_RS,
                UnidadeFederativa.RS, null);
        when(sefazIngestionService.fetch(CHAVE_RS)).thenReturn(fetched);
        when(sefazIngestionService.parse(fetched)).thenReturn(sampleParsed());
        when(receiptRepository.save(any(Receipt.class))).thenAnswer(inv -> {
            var r = inv.<Receipt>getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        var response = receiptService.submit(user, new SubmitReceiptRequest(CHAVE_RS));

        assertNotNull(response.id());
        assertEquals(ReceiptStatus.PENDING_CONFIRMATION, response.status());
        assertEquals(1, response.items().size());
        assertEquals("ARROZ TIO J 5KG", response.items().get(0).rawDescription());
    }

    @Test
    void submit_throwsPaywallWhenFreeUserAtMonthlyCap() {
        var user = buildUser();
        when(subscriptionGate.monthlyReceiptLimit(any())).thenReturn(5);
        when(receiptRepository.countByUserIdAndCreatedAtGreaterThanEqual(eq(user.getId()), any()))
                .thenReturn(5L);

        assertThrows(PaywallException.class,
                () -> receiptService.submit(user, new SubmitReceiptRequest(CHAVE_RS)));

        verify(sefazIngestionService, never()).fetch(any());
        verify(receiptRepository, never()).save(any());
    }

    @Test
    void submit_allowsWhenFreeUserUnderMonthlyCap() {
        var user = buildUser();
        when(subscriptionGate.monthlyReceiptLimit(any())).thenReturn(5);
        when(receiptRepository.countByUserIdAndCreatedAtGreaterThanEqual(eq(user.getId()), any()))
                .thenReturn(2L);
        when(receiptRepository.findByHouseholdIdAndChaveAcesso(any(), eq(CHAVE_RS))).thenReturn(Optional.empty());
        var fetched = new SefazIngestionService.FetchedDocument(null, "<html/>", CHAVE_RS,
                UnidadeFederativa.RS, null);
        when(sefazIngestionService.fetch(CHAVE_RS)).thenReturn(fetched);
        when(sefazIngestionService.parse(fetched)).thenReturn(sampleParsed());
        when(receiptRepository.save(any(Receipt.class))).thenAnswer(inv -> {
            var r = inv.<Receipt>getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        var response = receiptService.submit(user, new SubmitReceiptRequest(CHAVE_RS));

        assertEquals(ReceiptStatus.PENDING_CONFIRMATION, response.status());
    }

    @Test
    void submit_rejectsDuplicateOnlyWhenPriorIsConfirmed() {
        var user = buildUser();
        var existingConfirmed = persistedReceipt(user, ReceiptStatus.CONFIRMED);
        when(receiptRepository.findByHouseholdIdAndChaveAcesso(any(), eq(CHAVE_RS)))
                .thenReturn(Optional.of(existingConfirmed));

        assertThrows(ReceiptAlreadyIngestedException.class,
                () -> receiptService.submit(user, new SubmitReceiptRequest(CHAVE_RS)));

        verify(receiptRepository, never()).save(any());
        verify(receiptRepository, never()).delete(any(Receipt.class));
    }

    @Test
    void submit_replacesPriorPendingReceiptInsteadOfBlocking() {
        // FE-reported bug: user closed the app mid-review, then can't re-scan.
        // Non-CONFIRMED prior rows must be deleted to make room for the fresh submission.
        var user = buildUser();
        var stale = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        when(receiptRepository.findByHouseholdIdAndChaveAcesso(any(), eq(CHAVE_RS)))
                .thenReturn(Optional.of(stale));
        var fetched = new SefazIngestionService.FetchedDocument(null, "<html/>", CHAVE_RS,
                UnidadeFederativa.RS, null);
        when(sefazIngestionService.fetch(CHAVE_RS)).thenReturn(fetched);
        when(sefazIngestionService.parse(fetched)).thenReturn(sampleParsed());
        when(receiptRepository.save(any(Receipt.class))).thenAnswer(inv -> {
            var r = inv.<Receipt>getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        var response = receiptService.submit(user, new SubmitReceiptRequest(CHAVE_RS));

        // delete(T) and delete(DeleteSpecification<T>) overload conflict — use
        // an explicitly-typed captor to disambiguate, then check identity.
        var captor = ArgumentCaptor.forClass(Receipt.class);
        verify(receiptRepository).delete(captor.capture());
        assertEquals(stale.getId(), captor.getValue().getId());
        assertEquals(ReceiptStatus.PENDING_CONFIRMATION, response.status());
    }

    @Test
    void delete_callsRepositoryDeleteForOwnedReceipt() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.CONFIRMED);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));

        receiptService.delete(user, receipt.getId());

        var captor = ArgumentCaptor.forClass(Receipt.class);
        verify(receiptRepository).delete(captor.capture());
        assertEquals(receipt.getId(), captor.getValue().getId());
    }

    @Test
    void delete_throwsWhenReceiptBelongsToAnotherHousehold() {
        var user = buildUser();
        var stranger = buildUser();
        var receipt = persistedReceipt(stranger, ReceiptStatus.CONFIRMED);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));

        assertThrows(ReceiptNotFoundException.class, () -> receiptService.delete(user, receipt.getId()));
        verify(receiptRepository, never()).delete(any(Receipt.class));
    }

    @Test
    void delete_throwsWhenReceiptDoesNotExist() {
        var user = buildUser();
        var missingId = UUID.randomUUID();
        when(receiptRepository.findByIdWithItemsAndProducts(missingId)).thenReturn(Optional.empty());

        assertThrows(ReceiptNotFoundException.class, () -> receiptService.delete(user, missingId));
        verify(receiptRepository, never()).delete(any(Receipt.class));
    }

    @Test
    void confirm_marksReceiptConfirmedAndStampsTime() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));
        when(receiptRepository.save(receipt)).thenReturn(receipt);
        when(promoDetector.detectPersonalPromos(receipt)).thenReturn(List.of());

        var response = receiptService.confirm(user, receipt.getId(), null);

        assertEquals(ReceiptStatus.CONFIRMED, response.receipt().status());
        assertNotNull(response.receipt().confirmedAt());
        assertEquals(0, response.personalPromos().size());
    }

    @Test
    void confirm_throwsForOtherHouseholdReceipt() {
        var user = buildUser();
        var stranger = buildUser();
        var receipt = persistedReceipt(stranger, ReceiptStatus.PENDING_CONFIRMATION);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));

        assertThrows(ReceiptNotFoundException.class,
                () -> receiptService.confirm(user, receipt.getId(), null));
    }

    @Test
    void confirm_throwsWhenAlreadyConfirmed() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.CONFIRMED);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));

        assertThrows(ReceiptNotEditableException.class,
                () -> receiptService.confirm(user, receipt.getId(), null));
    }

    @Test
    void updateItem_modifiesItemFields() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        var item = receipt.getItems().get(0);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));

        var request = new UpdateReceiptItemRequest(
                "IGNORED — rawDescription is immutable",
                "7891234567890",
                new BigDecimal("3"),
                "UN",
                new BigDecimal("28.90"),
                new BigDecimal("86.70"),
                null,
                "Arroz Tio João 5kg"
        );

        var response = receiptService.updateItem(user, receipt.getId(), item.getId(), request);

        var updated = response.items().get(0);
        // rawDescription stays as the original (immutable audit text)
        assertEquals("ARROZ TIO J 5KG", updated.rawDescription());
        // friendlyDescription captures the user's display rename
        assertEquals("Arroz Tio João 5kg", updated.friendlyDescription());
        // displayDescription is the resolved field — friendly when set, raw otherwise
        assertEquals("Arroz Tio João 5kg", updated.displayDescription());
        assertEquals(new BigDecimal("3"), updated.quantity());
        assertEquals(new BigDecimal("86.70"), updated.totalPrice());
    }

    @Test
    void updateItem_throwsForUnknownItem() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));

        var request = new UpdateReceiptItemRequest("X", null,
                new BigDecimal("1"), null, null, new BigDecimal("1"), null, null);

        assertThrows(ReceiptItemNotFoundException.class,
                () -> receiptService.updateItem(user, receipt.getId(), UUID.randomUUID(), request));
    }

    @Test
    void reject_marksReceiptRejected() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));
        when(receiptRepository.save(receipt)).thenReturn(receipt);

        var response = receiptService.reject(user, receipt.getId());

        assertEquals(ReceiptStatus.REJECTED, response.status());
    }
}
