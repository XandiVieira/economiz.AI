package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.CaptchaUnavailableException;
import com.relyon.economizai.exception.ReceiptParseException;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import com.relyon.economizai.service.sefaz.captcha.CaptchaSolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * NFC-e adapter for Mato Grosso do Sul, whose consult portal
 * ({@code www.dfe.ms.gov.br/nfce/consulta}) gates the DANFE behind a Google
 * reCAPTCHA v2. Flow: GET the consult page → if it's the captcha wall, extract
 * the sitekey, get a token from the configured {@link CaptchaSolver}, resubmit
 * → parse the resulting DANFE with the shared {@link ResponsiveDanfeParser}
 * (MS serves the same responsive layout as RS/PR post-captcha).
 *
 * <p>When no solver is configured (the default), this fails fast with
 * {@link CaptchaUnavailableException} (503) so MS receipts get a clear "not
 * enabled yet" message rather than a confusing parse error — the day a solver
 * key is set, the same path just works.
 *
 * <p><b>Unverified until first real solve:</b> we have the captcha page (sitekey
 * extraction is fixture-tested) but not the post-captcha DANFE, since we can't
 * pass the captcha without a solver. The resubmit request shape in
 * {@link #fetchAuthorizedDanfe} and the consult-URL params are best-effort and
 * isolated so they're a quick adjustment once a solver lets us see a real page.
 */
@Slf4j
@Component
public class MsDfePortalAdapter implements SefazAdapter {

    private static final String CONSULT_URL = "https://www.dfe.ms.gov.br/nfce/consulta/";
    private static final Pattern SITE_KEY = Pattern.compile("data-sitekey=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern CAPTCHA_MARKER = Pattern.compile("g-recaptcha|recaptcha/api\\.js", Pattern.CASE_INSENSITIVE);

    private final CaptchaSolver captchaSolver;
    private final RestClient restClient;
    private final Set<UnidadeFederativa> supportedStates;

    public MsDfePortalAdapter(RestClient.Builder builder,
                              CaptchaSolver captchaSolver,
                              @Value("${economizai.ingestion.sefaz.captcha.states:MS}") String captchaStates,
                              @Value("${economizai.ingestion.sefaz.timeout-ms:30000}") int timeoutMs,
                              @Value("${economizai.ingestion.sefaz.user-agent:economizai}") String userAgent) {
        this.captchaSolver = captchaSolver;
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.min(timeoutMs, 10000));
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = builder
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "text/html,application/xhtml+xml")
                .requestFactory(requestFactory)
                .build();
        this.supportedStates = parseStates(captchaStates);
        log.info("MsDfePortalAdapter active for UFs: {} (captchaSolver configured={})",
                this.supportedStates, captchaSolver.isConfigured());
    }

    private Set<UnidadeFederativa> parseStates(String csv) {
        if (csv == null || csv.isBlank()) return EnumSet.of(UnidadeFederativa.MS);
        var result = EnumSet.noneOf(UnidadeFederativa.class);
        for (var token : csv.split(",")) {
            var trimmed = token.trim().toUpperCase();
            if (trimmed.isEmpty()) continue;
            try {
                result.add(UnidadeFederativa.valueOf(trimmed));
            } catch (IllegalArgumentException ex) {
                log.warn("Ignoring unknown UF '{}' in captcha.states", trimmed);
            }
        }
        return result.isEmpty() ? EnumSet.of(UnidadeFederativa.MS) : result;
    }

    @Override
    public Set<UnidadeFederativa> supportedStates() {
        return supportedStates;
    }

    @Override
    public String fetchHtml(String qrPayload) {
        var chave = ChaveAcessoParser.extractChave(qrPayload);
        var pageUrl = consultUrl(chave);
        var html = httpGet(pageUrl);
        if (html == null || !looksLikeCaptcha(html)) {
            // Portal already served the DANFE (no captcha this time) — parse as-is.
            return html;
        }
        var siteKey = extractSiteKey(html);
        if (siteKey == null) {
            log.warn("ms.captcha.no_sitekey chave={}", chave);
            throw new ReceiptParseException("captcha-sitekey-missing");
        }
        if (!captchaSolver.isConfigured()) {
            log.info("ms.captcha.unavailable chave={} (no solver configured)", chave);
            throw new CaptchaUnavailableException(UnidadeFederativa.MS.name());
        }
        log.info("ms.captcha.solving chave={} siteKey={}", chave, siteKey);
        var token = captchaSolver.solveRecaptchaV2(siteKey, pageUrl);
        return fetchAuthorizedDanfe(chave, token);
    }

    @Override
    public ParsedReceipt parseHtml(String html, String chaveAcesso, String sourceUrl) {
        return ResponsiveDanfeParser.parse(html, chaveAcesso, sourceUrl);
    }

    private String consultUrl(String chave) {
        return CONSULT_URL + "?tpAmb=1&chNFe=" + chave;
    }

    static boolean looksLikeCaptcha(String html) {
        return CAPTCHA_MARKER.matcher(html).find();
    }

    static String extractSiteKey(String html) {
        var matcher = SITE_KEY.matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** GET seam — isolated so the captcha orchestration is unit-testable. */
    protected String httpGet(String url) {
        return restClient.get().uri(url).retrieve().body(String.class);
    }

    /**
     * Resubmits the consult with the solved token to retrieve the DANFE. The
     * exact request shape (the consult posts {@code chNFe} + the
     * {@code g-recaptcha-response} token) is UNVERIFIED until the first real
     * solve — isolated here so it's a quick fix when we can see a live page.
     */
    protected String fetchAuthorizedDanfe(String chave, String recaptchaToken) {
        return restClient.post()
                .uri(CONSULT_URL)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body("tpAmb=1&chNFe=" + chave + "&g-recaptcha-response=" + recaptchaToken)
                .retrieve()
                .body(String.class);
    }
}
