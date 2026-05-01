package com.relyon.economizai.service;

import com.relyon.economizai.dto.request.SubmitReceiptRequest;
import com.relyon.economizai.dto.request.UpdateReceiptItemRequest;
import com.relyon.economizai.exception.ReceiptAlreadyIngestedException;
import com.relyon.economizai.exception.ReceiptItemNotFoundException;
import com.relyon.economizai.exception.ReceiptNotEditableException;
import com.relyon.economizai.exception.ReceiptNotFoundException;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import com.relyon.economizai.repository.ReceiptItemRepository;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.service.canonicalization.CanonicalizationService;
import com.relyon.economizai.service.sefaz.ParsedReceipt;
import com.relyon.economizai.service.sefaz.ParsedReceiptItem;
import com.relyon.economizai.service.sefaz.SefazIngestionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
    @Mock private com.relyon.economizai.service.priceindex.PriceIndexService priceIndexService;
    @Mock private com.relyon.economizai.service.priceindex.PromoDetector promoDetector;
    @Mock private com.relyon.economizai.service.geo.MarketLocationService marketLocationService;

    @InjectMocks private ReceiptService receiptService;

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
                .issuedAt(LocalDateTime.of(2026, 4, 15, 18, 0))
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
        when(receiptRepository.existsByChaveAcesso(CHAVE_RS)).thenReturn(false);
        when(sefazIngestionService.ingest(CHAVE_RS)).thenReturn(sampleParsed());
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
    void submit_rejectsDuplicateChave() {
        var user = buildUser();
        when(receiptRepository.existsByChaveAcesso(CHAVE_RS)).thenReturn(true);

        assertThrows(ReceiptAlreadyIngestedException.class,
                () -> receiptService.submit(user, new SubmitReceiptRequest(CHAVE_RS)));

        verify(receiptRepository, never()).save(any());
    }

    @Test
    void confirm_marksReceiptConfirmedAndStampsTime() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));
        when(receiptRepository.save(receipt)).thenReturn(receipt);
        when(promoDetector.detectPersonalPromos(receipt)).thenReturn(java.util.List.of());

        var response = receiptService.confirm(user, receipt.getId());

        assertEquals(ReceiptStatus.CONFIRMED, response.receipt().status());
        assertNotNull(response.receipt().confirmedAt());
        assertEquals(0, response.personalPromos().size());
    }

    @Test
    void confirm_throwsForOtherHouseholdReceipt() {
        var user = buildUser();
        var stranger = buildUser();
        var receipt = persistedReceipt(stranger, ReceiptStatus.PENDING_CONFIRMATION);
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));

        assertThrows(ReceiptNotFoundException.class,
                () -> receiptService.confirm(user, receipt.getId()));
    }

    @Test
    void confirm_throwsWhenAlreadyConfirmed() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.CONFIRMED);
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));

        assertThrows(ReceiptNotEditableException.class,
                () -> receiptService.confirm(user, receipt.getId()));
    }

    @Test
    void updateItem_modifiesItemFields() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        var item = receipt.getItems().get(0);
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));

        var request = new UpdateReceiptItemRequest(
                "ARROZ TIO JOAO TIPO 1 5KG",
                "7891234567890",
                new BigDecimal("3"),
                "UN",
                new BigDecimal("28.90"),
                new BigDecimal("86.70")
        );

        var response = receiptService.updateItem(user, receipt.getId(), item.getId(), request);

        assertEquals("ARROZ TIO JOAO TIPO 1 5KG", response.items().get(0).rawDescription());
        assertEquals(new BigDecimal("3"), response.items().get(0).quantity());
        assertEquals(new BigDecimal("86.70"), response.items().get(0).totalPrice());
    }

    @Test
    void updateItem_throwsForUnknownItem() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));

        var request = new UpdateReceiptItemRequest("X", null,
                new BigDecimal("1"), null, null, new BigDecimal("1"));

        assertThrows(ReceiptItemNotFoundException.class,
                () -> receiptService.updateItem(user, receipt.getId(), UUID.randomUUID(), request));
    }

    @Test
    void reject_marksReceiptRejected() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));
        when(receiptRepository.save(receipt)).thenReturn(receipt);

        var response = receiptService.reject(user, receipt.getId());

        assertEquals(ReceiptStatus.REJECTED, response.status());
    }
}
