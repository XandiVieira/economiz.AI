package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.ReceiptParseException;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.service.sefaz.SefazIngestionService.FetchedDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FailedParseRecorderTest {

    private static final String CHAVE = "43210912345678000199650010000001231000001239";
    private static final String QR_PAYLOAD = "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=" + CHAVE;
    private static final String SOURCE_URL = "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx";
    private static final String RAW_HTML = "<html><body>broken receipt</body></html>";

    @Mock private ReceiptRepository receiptRepository;

    @InjectMocks private FailedParseRecorder failedParseRecorder;

    private User userWithHousehold() {
        var household = Household.builder().id(UUID.randomUUID()).build();
        return User.builder().id(UUID.randomUUID()).email("maria@example.com").household(household).build();
    }

    private FetchedDocument fetchedDocument() {
        return new FetchedDocument(null, RAW_HTML, CHAVE, UnidadeFederativa.RS, SOURCE_URL);
    }

    @Test
    void persistsFailedReceiptWithAllFieldsFromFetchedDocument() {
        var user = userWithHousehold();
        var fetched = fetchedDocument();
        var parseException = new ReceiptParseException("no-items-found");

        failedParseRecorder.record(user, QR_PAYLOAD, fetched, parseException);

        var receiptCaptor = ArgumentCaptor.forClass(Receipt.class);
        verify(receiptRepository).save(receiptCaptor.capture());
        var saved = receiptCaptor.getValue();
        assertSame(user, saved.getUser());
        assertSame(user.getHousehold(), saved.getHousehold());
        assertEquals(CHAVE, saved.getChaveAcesso());
        assertEquals(UnidadeFederativa.RS, saved.getUf());
        assertEquals(QR_PAYLOAD, saved.getQrPayload());
        assertEquals(SOURCE_URL, saved.getSourceUrl());
        assertEquals(RAW_HTML, saved.getRawHtml());
        assertEquals(ReceiptStatus.FAILED_PARSE, saved.getStatus());
    }

    @Test
    void parseErrorReasonCombinesMessageKeyAndArguments() {
        var user = userWithHousehold();
        var parseException = new ReceiptParseException("unparseable-total");

        failedParseRecorder.record(user, QR_PAYLOAD, fetchedDocument(), parseException);

        var receiptCaptor = ArgumentCaptor.forClass(Receipt.class);
        verify(receiptRepository).save(receiptCaptor.capture());
        var reason = receiptCaptor.getValue().getParseErrorReason();
        assertTrue(reason.startsWith("receipt.parse.failed:"), () -> "actual: " + reason);
        assertTrue(reason.contains("unparseable-total"), () -> "actual: " + reason);
    }
}
