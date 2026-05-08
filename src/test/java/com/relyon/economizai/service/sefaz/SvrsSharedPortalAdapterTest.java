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

class SvrsSharedPortalAdapterTest {

    private static final String CHAVE_RS = "43260412345678000190650010000123451123456780";

    private final SvrsSharedPortalAdapter adapter = new SvrsSharedPortalAdapter(
            RestClient.builder(), 5000, "test-agent", "RS");

    private String loadFixture() throws Exception {
        return loadFixture("nfce-sample.html");
    }

    private String loadFixture(String name) throws Exception {
        try (var stream = new ClassPathResource("fixtures/sefaz/rs/" + name).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void supportedStates_defaultsToRs() {
        assertTrue(adapter.supportedStates().contains(UnidadeFederativa.RS));
        assertEquals(1, adapter.supportedStates().size());
    }

    @Test
    void supportedStates_acceptsCsvOfMultipleUfs() {
        var multi = new SvrsSharedPortalAdapter(RestClient.builder(), 5000, "test", "RS, SC, RJ");
        assertEquals(3, multi.supportedStates().size());
        assertTrue(multi.supportedStates().contains(UnidadeFederativa.SC));
        assertTrue(multi.supportedStates().contains(UnidadeFederativa.RJ));
    }

    @Test
    void supportedStates_ignoresUnknownUfTokens() {
        var partial = new SvrsSharedPortalAdapter(RestClient.builder(), 5000, "test", "RS,XX,SC");
        assertEquals(2, partial.supportedStates().size());
        assertTrue(partial.supportedStates().contains(UnidadeFederativa.RS));
        assertTrue(partial.supportedStates().contains(UnidadeFederativa.SC));
    }

    @Test
    void supportedStates_blankConfigFallsBackToRs() {
        var fallback = new SvrsSharedPortalAdapter(RestClient.builder(), 5000, "test", "");
        assertEquals(1, fallback.supportedStates().size());
        assertTrue(fallback.supportedStates().contains(UnidadeFederativa.RS));
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
    void parseHtml_distributesReceiptDiscountAcrossItems() throws Exception {
        var html = loadFixture("nfce-with-discount.html");
        var parsed = adapter.parseHtml(html, CHAVE_RS, null);

        assertEquals(new BigDecimal("76.00"), parsed.totalAmount());
        assertEquals(2, parsed.items().size());

        var arroz = parsed.items().get(0);
        assertEquals(new BigDecimal("47.50"), arroz.totalPrice());
        assertEquals(new BigDecimal("23.7500"), arroz.unitPrice());

        var leite = parsed.items().get(1);
        assertEquals(new BigDecimal("28.50"), leite.totalPrice());
        assertEquals(new BigDecimal("28.5000"), leite.unitPrice());

        var sum = parsed.items().stream()
                .map(ParsedReceiptItem::totalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("76.00"), sum);
    }

    @Test
    void parseHtml_extractsIbptApproxTaxWhenPresent() throws Exception {
        // Zaffari fixture carries: "Trib aprox R$ 51,73 Federal, R$ 49,35 Estadual Fonte: IBPT"
        var html = loadFixture("nfce-real-zaffari.html");
        var parsed = adapter.parseHtml(html, "43260493015006005182651130003394021410514546", null);
        assertEquals(0, parsed.approxTaxFederal().compareTo(new BigDecimal("51.73")));
        assertEquals(0, parsed.approxTaxEstadual().compareTo(new BigDecimal("49.35")));
    }

    @Test
    void parseHtml_returnsNullTaxWhenIbptLineMissing() throws Exception {
        // Synthetic sample fixture has no IBPT line — both fields must be null
        // so aggregations can filter receipts that didn't declare taxes.
        var html = CpfMasker.strip(loadFixture());
        var parsed = adapter.parseHtml(html, CHAVE_RS, null);
        assertEquals(null, parsed.approxTaxFederal());
        assertEquals(null, parsed.approxTaxEstadual());
    }

    @Test
    void parseHtml_stripsCpfBeforeStorage() throws Exception {
        var html = CpfMasker.strip(loadFixture());
        var parsed = adapter.parseHtml(html, CHAVE_RS, null);
        assertTrue(parsed.rawHtml().contains("***.***.***-**"));
        assertTrue(!parsed.rawHtml().contains("123.456.789-00"));
    }
}
