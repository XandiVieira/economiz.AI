package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.ReceiptParseException;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import com.relyon.economizai.repository.ReceiptRepository;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceiptIngestionServiceTest {

    private static final String CHAVE_RS = "43250612345678000190650010000012341000012345";
    private static final String QR = CHAVE_RS;

    @Mock private ReceiptRepository receiptRepository;
    @Mock private SefazIngestionService sefazIngestionService;

    @InjectMocks private ReceiptIngestionService service;

    private Receipt processingReceipt() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        var user = User.builder().id(UUID.randomUUID()).email("john@test.com").household(household).build();
        return Receipt.builder()
                .id(UUID.randomUUID())
                .user(user)
                .household(household)
                .chaveAcesso(CHAVE_RS)
                .uf(UnidadeFederativa.RS)
                .qrPayload(QR)
                .status(ReceiptStatus.PROCESSING)
                .build();
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

    @Test
    void ingest_success_fillsItemsAndMarksPendingConfirmation() {
        var receipt = processingReceipt();
        var fetched = new SefazIngestionService.FetchedDocument(null, "<html/>", CHAVE_RS, UnidadeFederativa.RS, null);
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));
        when(sefazIngestionService.fetch(QR)).thenReturn(fetched);
        when(sefazIngestionService.parse(fetched)).thenReturn(sampleParsed());

        service.ingest(receipt.getId(), QR);

        assertEquals(ReceiptStatus.PENDING_CONFIRMATION, receipt.getStatus());
        assertEquals(1, receipt.getItems().size());
        assertEquals("ARROZ TIO J 5KG", receipt.getItems().get(0).getRawDescription());
        assertEquals("Mercado X", receipt.getMarketName());
        verify(receiptRepository).save(receipt);
    }

    @Test
    void ingest_parseFailure_marksFailedParseWithReason() {
        var receipt = processingReceipt();
        var fetched = new SefazIngestionService.FetchedDocument(null, "<html/>", CHAVE_RS, UnidadeFederativa.RS, null);
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));
        when(sefazIngestionService.fetch(QR)).thenReturn(fetched);
        when(sefazIngestionService.parse(fetched)).thenThrow(new ReceiptParseException("no items found"));

        service.ingest(receipt.getId(), QR);

        assertEquals(ReceiptStatus.FAILED_PARSE, receipt.getStatus());
        verify(receiptRepository).save(receipt);
    }

    @Test
    void ingest_fetchThrows_marksFailedParse() {
        var receipt = processingReceipt();
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));
        when(sefazIngestionService.fetch(QR)).thenThrow(new RuntimeException("captcha timeout"));

        service.ingest(receipt.getId(), QR);

        var captor = ArgumentCaptor.forClass(Receipt.class);
        verify(receiptRepository).save(captor.capture());
        assertEquals(ReceiptStatus.FAILED_PARSE, captor.getValue().getStatus());
    }

    @Test
    void ingest_skipsWhenReceiptNoLongerProcessing() {
        var receipt = processingReceipt();
        receipt.setStatus(ReceiptStatus.CONFIRMED);
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));

        service.ingest(receipt.getId(), QR);

        verify(sefazIngestionService, never()).fetch(any());
        verify(receiptRepository, never()).save(any());
    }
}
