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

import org.springframework.http.ResponseEntity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
 * <p>The post-captcha DANFE layout is <b>verified</b> — a real MS receipt
 * parses correctly via the shared parser (see {@code RealMsFixtureTest}), and
 * the captcha page's sitekey extraction is fixture-tested. The one piece still
 * <b>unverified until the first real solve</b> is the HTTP transport that turns
 * a solved token into the DANFE: the consult-URL params and the resubmit shape
 * in {@link #fetchAuthorizedDanfe} (isolated here for a quick adjustment once a
 * solver lets us watch a live request).
 */
@Slf4j
@Component
public class MsDfePortalAdapter implements SefazAdapter {

    private static final String CONSULT_URL = "https://www.dfe.ms.gov.br/nfce/consulta/";
    private static final Pattern SITE_KEY = Pattern.compile("data-sitekey=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern CAPTCHA_MARKER = Pattern.compile("g-recaptcha|recaptcha/api\\.js", Pattern.CASE_INSENSITIVE);
    private static final Pattern SESSION_ID = Pattern.compile(";jsessionid=([a-zA-Z0-9]+)");
    private static final Pattern VIEW_STATE = Pattern.compile(
            "name=\"javax\\.faces\\.ViewState\"[^>]*value=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private final CaptchaSolver captchaSolver;
    private final RestClient restClient;
    private final RestClient noRedirectClient;
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
        // The MS portal responds to the captcha POST with a 302 redirect to /resultadoconsulta.
        // HttpURLConnection follows redirects automatically but strips the Cookie header,
        // so the result page comes back session-less (no items). We disable redirect-following
        // on this factory so we can manually follow with the session cookie.
        var noRedirectFactory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(java.net.HttpURLConnection connection, String httpMethod)
                    throws java.io.IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        noRedirectFactory.setConnectTimeout(Math.min(timeoutMs, 10000));
        noRedirectFactory.setReadTimeout(timeoutMs);
        this.noRedirectClient = builder
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "text/html,application/xhtml+xml")
                .requestFactory(noRedirectFactory)
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
        var response = httpGetResponse(pageUrl);
        var html = response.getBody();
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
        // Prefer Set-Cookie headers; fall back to sessionId from HTML if headers are unavailable
        // (HttpURLConnection may suppress Set-Cookie exposure when a CookieHandler is active).
        var cookieHeader = extractCookies(response.getHeaders().get("Set-Cookie"));
        if (cookieHeader == null) {
            var sessionId = extractSessionId(html);
            if (sessionId != null) cookieHeader = "JSESSIONID=" + sessionId;
        }
        return fetchAuthorizedDanfe(chave, html, token, cookieHeader);
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

    /** GET seam — returns the full response so cookies can be forwarded to the POST. */
    protected ResponseEntity<String> httpGetResponse(String url) {
        return restClient.get().uri(url).retrieve().toEntity(String.class);
    }

    /**
     * Resubmits the JSF consult form with the solved captcha token to retrieve
     * the DANFE. The portal uses JSF (Mojarra) — the POST must carry the
     * session-scoped ViewState and jsessionid from the captcha page, all visible
     * form fields, and the JSESSIONID cookie from the original GET (JSF validates
     * the ViewState against the server-side session).
     */
    protected String fetchAuthorizedDanfe(String chave, String captchaPageHtml,
                                          String recaptchaToken, String cookieHeader) {
        var sessionId = extractSessionId(captchaPageHtml);
        var viewState = extractViewState(captchaPageHtml);
        if (viewState == null) {
            log.warn("ms.captcha.no_viewstate chave={}", chave);
            throw new ReceiptParseException("captcha-viewstate-missing");
        }
        var postUrl = CONSULT_URL + (sessionId != null ? ";jsessionid=" + sessionId : "");
        var body = "formListar=formListar"
                + "&javax.faces.ViewState=" + enc(viewState)
                + "&" + enc("formListar:j_idt23") + "=1"
                + "&" + enc("formListar:j_idt27") + "=" + enc(chave)
                + "&g-recaptcha-response=" + enc(recaptchaToken)
                + "&" + enc("formListar:enter") + "=" + enc("formListar:enter");
        log.info("ms.captcha.submitting chave={} sessionId={}", chave, sessionId);
        var post = noRedirectClient.post()
                .uri(postUrl)
                .header("Content-Type", "application/x-www-form-urlencoded");
        if (cookieHeader != null && !cookieHeader.isBlank()) {
            post = post.header("Cookie", cookieHeader);
        }
        var postResponse = post.body(body).retrieve().toEntity(String.class);
        if (postResponse.getStatusCode().is3xxRedirection()) {
            var location = postResponse.getHeaders().getLocation();
            if (location == null) throw new ReceiptParseException("captcha-redirect-missing-location");
            log.info("ms.captcha.redirect chave={} location={}", chave, location);
            return getWithCookies(location.toString(), cookieHeader, chave);
        }
        return postResponse.getBody();
    }

    /**
     * GET the given URL with cookies preserved across protocol-upgrade redirects
     * (HTTP → HTTPS). HttpURLConnection never auto-follows those, so we detect a
     * 3xx and follow once more manually before reading the body.
     */
    private String getWithCookies(String url, String cookieHeader, String chave) {
        var getResp = noRedirectClient.get().uri(url)
                .headers(httpHeaders -> { if (cookieHeader != null && !cookieHeader.isBlank()) httpHeaders.set("Cookie", cookieHeader); })
                .retrieve()
                .toEntity(String.class);
        if (getResp.getStatusCode().is3xxRedirection()) {
            var next = getResp.getHeaders().getLocation();
            if (next == null) throw new ReceiptParseException("captcha-result-redirect-missing-location");
            log.info("ms.captcha.result.redirect chave={} location={}", chave, next);
            return noRedirectClient.get().uri(next)
                    .headers(httpHeaders -> { if (cookieHeader != null && !cookieHeader.isBlank()) httpHeaders.set("Cookie", cookieHeader); })
                    .retrieve()
                    .body(String.class);
        }
        return getResp.getBody();
    }

    private static String extractCookies(List<String> setCookieHeaders) {
        if (setCookieHeaders == null || setCookieHeaders.isEmpty()) return null;
        return setCookieHeaders.stream()
                .map(cookie -> cookie.split(";")[0])
                .collect(Collectors.joining("; "));
    }

    static String extractSessionId(String html) {
        var matcher = SESSION_ID.matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    static String extractViewState(String html) {
        var matcher = VIEW_STATE.matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
