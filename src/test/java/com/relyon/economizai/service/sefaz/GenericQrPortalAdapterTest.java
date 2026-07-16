package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.CaptchaUnavailableException;
import com.relyon.economizai.exception.InvalidQrPayloadException;
import com.relyon.economizai.exception.SefazFetchException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericQrPortalAdapterTest {

    // UF code 29 → BA (no dedicated adapter).
    private static final String CHAVE_BA = "29260412345678000190650010000123451123456780";
    private static final String QR_URL_BA = "https://nfe.sefaz.ba.gov.br/servicos/nfce/qrcode.aspx?p="
            + CHAVE_BA + "|2|1|1|deadbeef";

    private GenericQrPortalAdapter adapter(int maxAttempts, Function<String, String> http) {
        return new GenericQrPortalAdapter(RestClient.builder(), 1000, "test", true, maxAttempts, 0, "gov.br") {
            @Override
            protected String httpGet(String url) {
                return http.apply(url);
            }
        };
    }

    @Test
    void resolveUrl_acceptsGovBrHostsOnly() {
        var adapter = adapter(1, url -> "<html/>");

        assertEquals(QR_URL_BA, adapter.resolveUrl(QR_URL_BA));
        assertThrows(InvalidQrPayloadException.class,
                () -> adapter.resolveUrl("https://evil.example.com/qrcode?p=" + CHAVE_BA));
        assertThrows(InvalidQrPayloadException.class,
                () -> adapter.resolveUrl("https://gov.br.evil.com/qrcode?p=" + CHAVE_BA));
        assertThrows(InvalidQrPayloadException.class, () -> adapter.resolveUrl(CHAVE_BA));
    }

    @Test
    void fetchHtml_returnsBodyOnSuccess() {
        var adapter = adapter(1, url -> "<html>danfe</html>");

        assertEquals("<html>danfe</html>", adapter.fetchHtml(QR_URL_BA));
    }

    @Test
    void fetchHtml_retriesTransientFailuresThenSucceeds() {
        var calls = new AtomicInteger();
        var adapter = adapter(3, url -> {
            if (calls.incrementAndGet() < 3) throw new RestClientException("timeout");
            return "<html>ok</html>";
        });

        assertEquals("<html>ok</html>", adapter.fetchHtml(QR_URL_BA));
        assertEquals(3, calls.get());
    }

    @Test
    void fetchHtml_exhaustedRetriesThrowsSefazFetch() {
        var adapter = adapter(2, url -> {
            throw new RestClientException("down");
        });

        assertThrows(SefazFetchException.class, () -> adapter.fetchHtml(QR_URL_BA));
    }

    @Test
    void fetchHtml_clientErrorIsDeterministicNoRetry() {
        var calls = new AtomicInteger();
        var adapter = adapter(3, url -> {
            calls.incrementAndGet();
            throw new HttpClientErrorException(HttpStatus.NOT_FOUND);
        });

        assertThrows(SefazFetchException.class, () -> adapter.fetchHtml(QR_URL_BA));
        assertEquals(1, calls.get());
    }

    @Test
    void fetchHtml_captchaWallSignalsRescuableCaptchaUnavailable() {
        var adapter = adapter(1, url -> "<html><div class=\"g-recaptcha\" data-sitekey=\"x\"></div></html>");

        assertThrows(CaptchaUnavailableException.class, () -> adapter.fetchHtml(QR_URL_BA));
    }

    /**
     * The whole premise of the experimental chain: an unknown state whose portal
     * renders the shared responsive-DANFE layout parses with NO dedicated
     * adapter. Locked with the real PR fixture (PR has its own portal — exactly
     * the situation a new Tier-1 state would be in).
     */
    @Test
    void parseHtml_parsesResponsiveDanfeFromUnknownPortal() throws Exception {
        var html = new ClassPathResource("fixtures/sefaz/pr/nfce-real-raiadrogasil.html")
                .getContentAsString(StandardCharsets.UTF_8);
        var chave = "41260361585865261893650030000564031777660148";
        var adapter = adapter(1, url -> html);

        var parsed = adapter.parseHtml(html, chave, "https://www.fazenda.pr.gov.br/nfce/qrcode?p=" + chave);

        assertEquals(chave, parsed.chaveAcesso());
        assertTrue(parsed.items().size() >= 1);
    }
}
