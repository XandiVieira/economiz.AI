package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.CaptchaUnavailableException;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import com.relyon.economizai.service.sefaz.captcha.CaptchaSolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MS adapter — the captcha-orchestration (detect → solve → parse) is testable
 * with the real captcha-page fixture even though we can't reach a live DANFE.
 * The post-captcha page is stubbed with a known responsive-DANFE fixture to
 * prove parse delegation works once a solver is wired.
 */
class MsDfePortalAdapterTest {

    private static final String MS_CHAVE = "50260677863223012709650190004048511190344086";

    private String captchaPage() throws Exception {
        try (var stream = new ClassPathResource("fixtures/sefaz/ms/nfce-captcha-page.html").getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String rsDanfe() throws Exception {
        try (var stream = new ClassPathResource("fixtures/sefaz/rs/nfce-sample.html").getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static CaptchaSolver solver(boolean configured, String token) {
        return new CaptchaSolver() {
            @Override public boolean isConfigured() { return configured; }
            @Override public String solveRecaptchaV2(String siteKey, String pageUrl) {
                if (!configured) throw new CaptchaUnavailableException("MS");
                return token;
            }
        };
    }

    @Test
    void supportedStates_isMs() {
        var adapter = new MsDfePortalAdapter(RestClient.builder(), solver(false, null), "MS", 5000, "test");
        assertTrue(adapter.supportedStates().contains(UnidadeFederativa.MS));
        assertEquals(1, adapter.supportedStates().size());
    }

    @Test
    void captchaPage_detectedAndSiteKeyExtracted() throws Exception {
        var html = captchaPage();
        assertTrue(MsDfePortalAdapter.looksLikeCaptcha(html));
        assertEquals("6Ld92rYUAAAAAEDBMjM9PhWRkjc10WuVFBOOYV0O", MsDfePortalAdapter.extractSiteKey(html));
    }

    @Test
    void fetchHtml_noSolverConfigured_failsFastWithCaptchaUnavailable() throws Exception {
        var captcha = captchaPage();
        var adapter = new MsDfePortalAdapter(RestClient.builder(), solver(false, null), "MS", 5000, "test") {
            @Override protected ResponseEntity<String> httpGetResponse(String url) { return ResponseEntity.ok(captcha); }
        };

        assertThrows(CaptchaUnavailableException.class, () -> adapter.fetchHtml(MS_CHAVE));
    }

    @Test
    void fetchHtml_solverConfigured_solvesThenReturnsDanfe() throws Exception {
        var captcha = captchaPage();
        var danfe = rsDanfe();
        var solveCalls = new AtomicInteger();
        var adapter = new MsDfePortalAdapter(RestClient.builder(),
                solver(true, "the-token"), "MS", 5000, "test") {
            @Override protected ResponseEntity<String> httpGetResponse(String url) { return ResponseEntity.ok(captcha); }
            @Override protected String fetchAuthorizedDanfe(String chave, String captchaPageHtml,
                                                            String recaptchaToken, String cookieHeader) {
                solveCalls.incrementAndGet();
                assertEquals("the-token", recaptchaToken);
                assertEquals(MS_CHAVE, chave);
                return danfe;
            }
        };

        var html = adapter.fetchHtml(MS_CHAVE);
        var parsed = adapter.parseHtml(html, MS_CHAVE, null);

        assertEquals(1, solveCalls.get());
        assertEquals(MS_CHAVE, parsed.chaveAcesso());
        assertTrue(parsed.items().size() >= 1);
        assertEquals(0, new BigDecimal("99.48").compareTo(parsed.totalAmount()));
    }

    @Test
    void fetchHtml_noCaptchaOnPage_returnsItDirectly() throws Exception {
        var danfe = rsDanfe();
        var adapter = new MsDfePortalAdapter(RestClient.builder(), solver(true, "tok"), "MS", 5000, "test") {
            @Override protected ResponseEntity<String> httpGetResponse(String url) { return ResponseEntity.ok(danfe); }
            @Override protected String fetchAuthorizedDanfe(String chave, String captchaPageHtml,
                                                            String recaptchaToken, String cookieHeader) {
                throw new AssertionError("should not resubmit when no captcha present");
            }
        };

        var html = adapter.fetchHtml(MS_CHAVE);
        assertEquals(danfe, html);
    }

    @Test
    void parseHtml_delegatesToResponsiveDanfeParser() throws Exception {
        var adapter = new MsDfePortalAdapter(RestClient.builder(), solver(false, null), "MS", 5000, "test");
        var parsed = adapter.parseHtml(rsDanfe(), MS_CHAVE, null);
        assertTrue(parsed.marketName().contains("ZAFFARI"));
    }
}
