package com.relyon.economizai.service.sefaz;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anchor test against a real Zaffari NFC-e that carries a receipt-level
 * discount AND an IBPT-source approximate-tax line. Locks in two behaviors
 * we don't want to regress:
 *
 * <ul>
 *   <li>{@code reconcileItemsToTotal} distributes the R$ 5,84 discount
 *       proportionally so every per-item totalPrice reflects the household's
 *       paid share, and the items sum back to the printed grand total.</li>
 *   <li>{@code parseApproxTax} extracts the IBPT line as
 *       (federal=15.13, estadual=13.73). This is what powers the
 *       "carga tributária aproximada" UX.</li>
 * </ul>
 */
class RealDiscountFixtureTest {

    private static final String CHAVE = "43260593015006000709651090005672911845634822";

    private final SvrsSharedPortalAdapter adapter = new SvrsSharedPortalAdapter(
            RestClient.builder(), 5000, "test-agent", "RS");

    @Test
    void parseDiscountReceipt_distributesAndExtractsTax() throws Exception {
        var html = new String(new ClassPathResource("fixtures/sefaz/rs/nfce-real-discount.html")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var parsed = adapter.parseHtml(html, CHAVE, "https://test/source");

        assertEquals("93015006000709", parsed.cnpjEmitente());
        assertNotNull(parsed.marketName());

        // grand total = "Valor a pagar R$ 106,57" — what was actually paid
        assertEquals(0, parsed.totalAmount().compareTo(new BigDecimal("106.57")));
        assertEquals(6, parsed.items().size());

        // items printed sum to 112.41; reconcile must distribute the 5.84 gap
        var sum = parsed.items().stream()
                .map(ParsedReceiptItem::totalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(new BigDecimal("106.57")),
                "post-reconcile item sum must equal grand total within rounding");

        // IBPT-source approximate tax — surfaces "Trib aprox R$ 15,13 Federal, R$ 13,73 Estadual"
        assertEquals(0, parsed.approxTaxFederal().compareTo(new BigDecimal("15.13")));
        assertEquals(0, parsed.approxTaxEstadual().compareTo(new BigDecimal("13.73")));

        // CPF must have been redacted before this fixture was committed
        assertTrue(parsed.rawHtml().contains("000.000.000-00"));
        assertTrue(!parsed.rawHtml().matches("(?s).*041\\.603\\.690-22.*"));
    }
}
