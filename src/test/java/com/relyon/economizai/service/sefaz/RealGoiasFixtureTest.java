package com.relyon.economizai.service.sefaz;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real GO NFC-e captured 2026-07-22 from an organic user scan (Cencosud
 * Goiânia, 6 items, R$94,14). Two fixtures: the render page as the portal
 * serves it (DANFE embedded as an escaped JS string) and the extracted
 * standard-layout DANFE — proving both the extraction seam and the parse.
 */
class RealGoiasFixtureTest {

    private static final String CHAVE = "52260739346861022483650190000191021190388509";

    private String fixture(String name) throws Exception {
        return new String(new ClassPathResource("fixtures/sefaz/go/" + name)
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void extractsEmbeddedDanfeFromRenderPage() throws Exception {
        var extracted = GoiasNfcePortalAdapter.extractEmbeddedDanfe(fixture("nfce-render-page-with-embedded-danfe.html"));

        assertNotNull(extracted);
        assertTrue(extracted.contains("tabResult"), "embed must be the standard DANFE markup");
        assertTrue(extracted.contains("CENCOSUD"));
    }

    @Test
    void extractedDanfe_returnsNullWithoutEmbed() {
        assertEquals(null, GoiasNfcePortalAdapter.extractEmbeddedDanfe(
                "<html><script>new DanfeNFCe('#danfe-nfce-container','/nfeweb/imagens/',null);</script></html>"));
    }

    @Test
    void parsesRealGoReceiptEndToEnd() throws Exception {
        var danfe = GoiasNfcePortalAdapter.extractEmbeddedDanfe(
                fixture("nfce-render-page-with-embedded-danfe.html"));
        var parsed = ScNfceDanfeParser.parse(danfe, CHAVE, "https://test/source");

        assertEquals(CHAVE, parsed.chaveAcesso());
        assertEquals("39346861022483", parsed.cnpjEmitente());
        assertTrue(parsed.marketName().contains("CENCOSUD"));
        assertEquals(6, parsed.items().size());
        assertEquals(0, parsed.totalAmount().compareTo(new BigDecimal("94.14")));
        assertEquals(2026, parsed.issuedAt().getYear());
        var firstItem = parsed.items().get(0);
        assertTrue(firstItem.rawDescription().contains("PAO FRANCES"));
        assertEquals(0, firstItem.quantity().compareTo(new BigDecimal("0.234")));
        assertEquals("KG", firstItem.unit());
    }

    @Test
    void buildsSessionPinnedRenderUrl() {
        assertEquals("https://nfeweb.sefaz.go.gov.br/nfeweb/sites/nfce/render/danfeNFCe;jsessionid=abc.jbprodeap17:eap08?chNFe=" + CHAVE,
                GoiasNfcePortalAdapter.renderUrl(CHAVE, "abc.jbprodeap17:eap08"));
        assertEquals("https://nfeweb.sefaz.go.gov.br/nfeweb/sites/nfce/render/danfeNFCe?chNFe=" + CHAVE,
                GoiasNfcePortalAdapter.renderUrl(CHAVE, null));
    }

    @Test
    void extractsJsessionIdFromShellHtml() throws Exception {
        // the COOKIELESS shell (what the adapter's first request sees) carries
        // JBoss URL-rewritten links; with cookies the rewriting disappears.
        var sessionId = GoiasNfcePortalAdapter.extractJsessionId(fixture("nfce-shell-cookieless.html"));
        assertEquals("v0Bm0JlzbNv6cHF1zMhq8cWeM9FT0PqvNEl4rYOJ.jbprodeap18:eap09", sessionId);
    }
}
