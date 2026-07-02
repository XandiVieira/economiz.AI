package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.CaptchaUnavailableException;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import com.relyon.economizai.service.sefaz.captcha.CaptchaSolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SantaCatarinaNfcePortalAdapterTest {

    private static final String CHAVE_SC = "42260650552333000100650080001188891101255904";
    private static final String SECURITY_URL = "https://sat.sef.sc.gov.br/tax.NET/SecurityVerify.aspx?rq=abc";
    private static final String FINAL_URL = "https://sat.sef.sc.gov.br/tax.NET/Sat.DFe.NFCe.Web/Consultas/NFCe_Detalhes.aspx?rq=def";

    @Test
    void supportedStates_containsSc() {
        var adapter = new SantaCatarinaNfcePortalAdapter(RestClient.builder(), solver(true), 30000, "test");

        assertEquals(Set.of(UnidadeFederativa.SC), adapter.supportedStates());
    }

    @Test
    void preflightChave_extractsOriginalChaveFromSecurityViewState() {
        var adapter = new TestScAdapter(solver(false));
        adapter.getResponses.put(SECURITY_URL, ResponseEntity.ok(securityHtml()));

        var chave = adapter.preflightChave(SECURITY_URL);

        assertEquals(CHAVE_SC, chave.orElseThrow());
    }

    @Test
    void fetchHtml_solvesTurnstilePostsAspNetFormAndFollowsRedirect() {
        var solver = solver(true);
        var adapter = new TestScAdapter(solver);
        adapter.getResponses.put(SECURITY_URL, ResponseEntity.ok()
                .header("Set-Cookie", "ASP.NET_SessionId=session-1; path=/")
                .body(securityHtml()));
        adapter.postResponse = ResponseEntity.status(302)
                .header("Location", FINAL_URL)
                .build();
        adapter.getResponses.put(FINAL_URL, ResponseEntity.ok(scDanfeHtml()));

        var html = adapter.fetchHtml(SECURITY_URL);

        assertEquals(scDanfeHtml(), html);
        assertEquals("0x4AAAAAAB2sElvZiQsYMEfK", solver.siteKey);
        assertEquals(SECURITY_URL, solver.pageUrl);
        assertEquals(SECURITY_URL, adapter.postedUrl);
        assertEquals("ASP.NET_SessionId=session-1", adapter.postedCookie);
        assertEquals("_ctl0$_ctl0$Body$Main$ButtonValidar", adapter.postedBody.getFirst("__EVENTTARGET"));
        assertEquals("turnstile-token", adapter.postedBody.getFirst("_ctl0:_ctl0:Body:Main:cf-turnstile-response"));
    }

    @Test
    void fetchHtml_withoutConfiguredSolverThrowsUnavailable() {
        var adapter = new TestScAdapter(solver(false));
        adapter.getResponses.put(SECURITY_URL, ResponseEntity.ok(securityHtml()));

        assertThrows(CaptchaUnavailableException.class, () -> adapter.fetchHtml(SECURITY_URL));
    }

    @Test
    void parseHtml_parsesScDetailsPage() {
        var adapter = new SantaCatarinaNfcePortalAdapter(RestClient.builder(), solver(false), 30000, "test");

        var parsed = adapter.parseHtml(scDanfeHtml(), CHAVE_SC, FINAL_URL);

        assertEquals(CHAVE_SC, parsed.chaveAcesso());
        assertEquals("50552333000100", parsed.cnpjEmitente());
        assertEquals("OTTO ATACAREJO COMERCIAL LTDA", parsed.marketName());
        assertEquals("AV MAXIMILIANO FUERBRINGER SC 486 , 518 , SOUZA CRUZ , BRUSQUE , SC", parsed.marketAddress());
        assertEquals(LocalDateTime.of(2026, 6, 30, 14, 31, 18), parsed.issuedAt());
        assertEquals(new BigDecimal("133.19"), parsed.totalAmount());
        assertEquals(3, parsed.items().size());
        assertEquals("CHOC KIT KAT 4 FNGR DARK 415GR", parsed.items().get(0).rawDescription());
        assertNull(parsed.items().get(0).ean(), "SC item code 28941 is merchant-internal, not an EAN");
        assertEquals(new BigDecimal("2"), parsed.items().get(0).quantity());
        assertEquals("UNID", parsed.items().get(0).unit());
        assertEquals(new BigDecimal("4.49"), parsed.items().get(0).unitPrice());
        assertEquals(new BigDecimal("8.98"), parsed.items().get(0).totalPrice());
    }

    @Test
    void extractChaveFromHtml_parsesPrintedChave() {
        assertEquals(CHAVE_SC, SantaCatarinaNfcePortalAdapter.extractChaveFromHtml(scDanfeHtml()).orElseThrow());
    }

    private static TestCaptchaSolver solver(boolean configured) {
        return new TestCaptchaSolver(configured);
    }

    private static String securityHtml() {
        var original = "~/Sat.Dfe.NFCe.Web/Consultas/ConsultaPublicaNFCe.aspx?p=" + CHAVE_SC + "%7C3%7C1";
        var viewState = Base64.getEncoder().encodeToString(original.getBytes(StandardCharsets.UTF_8));
        return """
                <html><body>
                  <form method="post" action="/tax.NET/SecurityVerify.aspx?rq=abc" id="Main">
                    <input type="hidden" name="__VIEWSTATE" value="%s"/>
                    <input type="hidden" name="__VIEWSTATEGENERATOR" value="GEN"/>
                    <input type="hidden" name="__EVENTVALIDATION" value="EV"/>
                    <div class="cf-turnstile" data-sitekey="0x4AAAAAAB2sElvZiQsYMEfK"></div>
                    <input type="hidden" name="_ctl0:_ctl0:Body:Main:cf-turnstile-response" id="Body_Main_cf-turnstile-response" value=""/>
                    <a href="javascript:__doPostBack('_ctl0$_ctl0$Body$Main$ButtonValidar','')">Validar</a>
                  </form>
                  Cloudflare
                </body></html>
                """.formatted(viewState);
    }

    private static String scDanfeHtml() {
        return """
                <html><body>
                  <h1>DOCUMENTO AUXILIAR DA NOTA FISCAL DE CONSUMIDOR ELETRONICA</h1>
                  <section>
                    OTTO ATACAREJO COMERCIAL LTDA
                    CNPJ: 50.552.333/0001-00
                    AV MAXIMILIANO FUERBRINGER SC 486 , 518 , SOUZA CRUZ , BRUSQUE , SC
                  </section>
                  <div class="item">CHOC KIT KAT 4 FNGR DARK 415GR (Código: 28941 ) Qtde.:2 UN: UNID Vl. Unit.: 4,49 Vl. Total 8,98</div>
                  <div class="item">PAPEL HIG BOB PREMIUM FDUPLA C32 ROLO (Código: 35561 ) Qtde.:1 UN: UNID Vl. Unit.: 32,99 Vl. Total 32,99</div>
                  <div class="item">PAO FRANCES KG (Código: 37 ) Qtde.:0,39 UN: KG Vl. Unit.: 16,99 Vl. Total 6,63</div>
                  <div>Cartão de Débito 133,19</div>
                  <div>Informações gerais da Nota</div>
                  <div>Número: 118889 Série: 8 Emissão: 30/06/2026 14:31:18 - Via Consumidor 2</div>
                  <div>Chave de acesso:</div>
                  <div>4226 0650 5523 3300 0100 6500 8000 1188 8911 0125 5904</div>
                </body></html>
                """;
    }

    private static final class TestCaptchaSolver implements CaptchaSolver {
        private final boolean configured;
        private String siteKey;
        private String pageUrl;

        private TestCaptchaSolver(boolean configured) {
            this.configured = configured;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public String solveRecaptchaV2(String siteKey, String pageUrl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String solveCloudflareTurnstile(String siteKey, String pageUrl) {
            this.siteKey = siteKey;
            this.pageUrl = pageUrl;
            return "turnstile-token";
        }
    }

    private static final class TestScAdapter extends SantaCatarinaNfcePortalAdapter {
        private final Map<String, ResponseEntity<String>> getResponses = new HashMap<>();
        private ResponseEntity<String> postResponse;
        private String postedUrl;
        private String postedCookie;
        private MultiValueMap<String, String> postedBody;

        private TestScAdapter(CaptchaSolver solver) {
            super(RestClient.builder(), solver, 30000, "test");
        }

        @Override
        protected ResponseEntity<String> httpGetResponse(String url) {
            assertTrue(getResponses.containsKey(url), "unexpected GET " + url);
            return getResponses.get(url);
        }

        @Override
        protected ResponseEntity<String> httpGetResponse(String url, String cookieHeader) {
            assertTrue(getResponses.containsKey(url), "unexpected GET " + url);
            return getResponses.get(url);
        }

        @Override
        protected ResponseEntity<String> httpPostForm(String url, MultiValueMap<String, String> body, String cookieHeader) {
            this.postedUrl = url;
            this.postedBody = body;
            this.postedCookie = cookieHeader;
            return postResponse;
        }
    }
}
