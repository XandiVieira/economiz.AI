package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.ReceiptParseException;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RioGrandeDoSulAdapterTest {

    private static final String CHAVE_RS = "43260412345678000190650010000123451123456780";

    private final RioGrandeDoSulAdapter adapter = new RioGrandeDoSulAdapter(
            RestClient.builder(), 5000, "test-agent");

    private String loadFixture() throws Exception {
        try (var stream = new ClassPathResource("fixtures/sefaz/rs/nfce-sample.html").getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void supportedState_isRs() {
        assertEquals(UnidadeFederativa.RS, adapter.supportedState());
    }

    @Test
    void resolveUrl_acceptsFullUrl() {
        var url = "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=" + CHAVE_RS;
        assertEquals(url, adapter.resolveUrl(url));
    }

    @Test
    void resolveUrl_buildsUrlFromParam() {
        var resolved = adapter.resolveUrl(CHAVE_RS + "|2|1|1|hash");
        assertTrue(resolved.startsWith("https://dfe-portal.svrs.rs.gov.br/Dfe/QrCodeNFce?p="));
        assertTrue(resolved.contains(CHAVE_RS));
    }

    @Test
    void parseHtml_extractsItemsAndMetadata() throws Exception {
        var html = CpfMasker.strip(loadFixture());
        var parsed = adapter.parseHtml(html, CHAVE_RS, "https://example/test");

        assertEquals(CHAVE_RS, parsed.chaveAcesso());
        assertEquals("12345678000190", parsed.cnpjEmitente());
        assertNotNull(parsed.marketName());
        assertTrue(parsed.marketName().contains("ZAFFARI"));
        assertEquals(new BigDecimal("99.48"), parsed.totalAmount());
        assertNotNull(parsed.issuedAt());
        assertEquals(2026, parsed.issuedAt().getYear());

        assertEquals(3, parsed.items().size());

        var arroz = parsed.items().get(0);
        assertEquals(1, arroz.lineNumber());
        assertTrue(arroz.rawDescription().contains("ARROZ"));
        assertEquals("7891234567890", arroz.ean());
        assertEquals(new BigDecimal("2"), arroz.quantity());
        assertEquals("UN", arroz.unit());
        assertEquals(new BigDecimal("28.90"), arroz.unitPrice());
        assertEquals(new BigDecimal("57.80"), arroz.totalPrice());

        var banana = parsed.items().get(2);
        assertEquals(new BigDecimal("1.250"), banana.quantity());
        assertEquals("KG", banana.unit());
        assertEquals(new BigDecimal("8.74"), banana.totalPrice());
    }

    @Test
    void parseHtml_throwsWhenNoItems() {
        var html = "<html><body><div>no items here</div></body></html>";
        assertThrows(ReceiptParseException.class, () -> adapter.parseHtml(html, CHAVE_RS, null));
    }

    @Test
    void parseHtml_stripsCpfBeforeStorage() throws Exception {
        var html = CpfMasker.strip(loadFixture());
        var parsed = adapter.parseHtml(html, CHAVE_RS, null);
        assertTrue(parsed.rawHtml().contains("***.***.***-**"));
        assertTrue(!parsed.rawHtml().contains("123.456.789-00"));
    }
}
