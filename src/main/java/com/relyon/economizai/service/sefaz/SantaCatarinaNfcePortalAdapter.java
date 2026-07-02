package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.CaptchaSolveFailedException;
import com.relyon.economizai.exception.CaptchaUnavailableException;
import com.relyon.economizai.exception.InvalidQrPayloadException;
import com.relyon.economizai.exception.ReceiptParseException;
import com.relyon.economizai.exception.SefazFetchException;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import com.relyon.economizai.service.privacy.LogMasker;
import com.relyon.economizai.service.sefaz.captcha.CaptchaSolver;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class SantaCatarinaNfcePortalAdapter implements SefazAdapter {

    private static final String BASE_URL = "https://sat.sef.sc.gov.br";
    private static final String CONSULT_URL = BASE_URL + "/tax.NET/Sat.DFe.NFCe.Web/Consultas/ConsultaPublicaNFCe.aspx";
    private static final String TURNSTILE_FALLBACK_FIELD = "_ctl0:_ctl0:Body:Main:cf-turnstile-response";
    private static final String VALIDATE_EVENT_TARGET = "_ctl0$_ctl0$Body$Main$ButtonValidar";
    private static final Pattern URL_HOST = Pattern.compile(
            "^(https?)://([^/?#@\\\\]+?)(?::\\d+)?(?=[/?#]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHAVE = Pattern.compile("\\d{44}");

    private final CaptchaSolver captchaSolver;
    private final RestClient restClient;
    private final RestClient noRedirectClient;

    public SantaCatarinaNfcePortalAdapter(RestClient.Builder builder,
                                          CaptchaSolver captchaSolver,
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
        log.info("SantaCatarinaNfcePortalAdapter active captchaSolver configured={}", captchaSolver.isConfigured());
    }

    @Override
    public Set<UnidadeFederativa> supportedStates() {
        return EnumSet.of(UnidadeFederativa.SC);
    }

    @Override
    public Optional<String> preflightChave(String qrPayload) {
        if (qrPayload == null || qrPayload.isBlank()) return Optional.empty();
        try {
            var url = resolveUrl(qrPayload);
            if (!isAllowedScUrl(url)) return Optional.empty();
            var html = httpGetResponse(url).getBody();
            return extractChaveFromHtml(html);
        } catch (RuntimeException ex) {
            log.debug("sc.preflight.failed reason={}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public String fetchHtml(String qrPayload) {
        var url = resolveUrl(qrPayload);
        try {
            var response = httpGetResponse(url);
            var html = response.getBody();
            if (html == null || html.isBlank()) {
                throw new SefazFetchException(UnidadeFederativa.SC.name());
            }
            if (!ScSecurityChallengeDetector.looksLikeHtml(html)) {
                return html;
            }
            return solveSecurityChallenge(html, url, response);
        } catch (CaptchaUnavailableException | CaptchaSolveFailedException | ReceiptParseException ex) {
            throw ex;
        } catch (RestClientException ex) {
            log.warn("sc.fetch.failed reason={}", ex.getClass().getSimpleName());
            throw new SefazFetchException(UnidadeFederativa.SC.name());
        }
    }

    @Override
    public ParsedReceipt parseHtml(String html, String chaveAcesso, String sourceUrl) {
        return ScNfceDanfeParser.parse(html, chaveAcesso, sourceUrl);
    }

    public String resolveUrl(String qrPayload) {
        var trimmed = qrPayload.trim();
        if (trimmed.toLowerCase().startsWith("http")) {
            if (!isAllowedScUrl(trimmed)) {
                log.warn("sc.url.rejected host not in allowlist");
                throw new InvalidQrPayloadException();
            }
            return encodeScQuerySeparators(trimmed);
        }
        var chave = ChaveAcessoParser.extractChave(qrPayload);
        return CONSULT_URL + "?p=" + chave + "%7C3%7C1";
    }

    private String solveSecurityChallenge(String securityHtml, String currentUrl, ResponseEntity<String> response) {
        if (!captchaSolver.isConfigured()) {
            throw new CaptchaUnavailableException(UnidadeFederativa.SC.name());
        }
        var siteKey = extractTurnstileSiteKey(securityHtml);
        if (siteKey == null) {
            throw new ReceiptParseException("turnstile-sitekey-missing");
        }
        var securityUrl = securityUrl(securityHtml, currentUrl);
        var chave = extractChaveFromHtml(securityHtml).orElse(null);
        log.info("sc.turnstile.solving chave={} siteKey={}", LogMasker.chave(chave), siteKey);
        var token = captchaSolver.solveCloudflareTurnstile(siteKey, securityUrl);
        var cookieHeader = extractCookies(response.getHeaders().get("Set-Cookie"));
        var postResponse = submitSecurityForm(securityHtml, securityUrl, token, cookieHeader);
        var danfe = followIfRedirect(postResponse, cookieHeader);
        if (danfe == null || danfe.isBlank()) {
            throw new SefazFetchException(UnidadeFederativa.SC.name());
        }
        if (ScSecurityChallengeDetector.looksLikeHtml(danfe)) {
            throw new CaptchaSolveFailedException("turnstile-rejected");
        }
        return danfe;
    }

    static Optional<String> extractChaveFromHtml(String html) {
        if (html == null || html.isBlank()) return Optional.empty();
        var parsed = ScNfceDanfeParser.extractChave(Jsoup.parse(html));
        if (parsed.isPresent()) return parsed;

        var document = Jsoup.parse(html);
        for (var input : document.select("input[name=__VIEWSTATE]")) {
            var decoded = decodeBase64Text(input.attr("value"));
            if (decoded == null) continue;
            var urlDecoded = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
            var matcher = CHAVE.matcher(urlDecoded);
            if (matcher.find()) return Optional.of(matcher.group());
        }
        return Optional.empty();
    }

    static String extractTurnstileSiteKey(String html) {
        var document = Jsoup.parse(html);
        var turnstile = document.selectFirst(".cf-turnstile[data-sitekey]");
        if (turnstile == null) return null;
        var siteKey = turnstile.attr("data-sitekey").trim();
        return siteKey.isBlank() ? null : siteKey;
    }

    protected ResponseEntity<String> httpGetResponse(String url) {
        return restClient.get().uri(url).retrieve().toEntity(String.class);
    }

    protected ResponseEntity<String> httpGetResponse(String url, String cookieHeader) {
        return noRedirectClient.get()
                .uri(url)
                .headers(headers -> {
                    if (cookieHeader != null && !cookieHeader.isBlank()) {
                        headers.set("Cookie", cookieHeader);
                    }
                })
                .retrieve()
                .toEntity(String.class);
    }

    protected ResponseEntity<String> submitSecurityForm(String securityHtml, String securityUrl,
                                                        String turnstileToken, String cookieHeader) {
        var document = Jsoup.parse(securityHtml, BASE_URL);
        var form = document.selectFirst("form");
        if (form == null) throw new ReceiptParseException("sc-security-form-missing");
        var action = form.hasAttr("action") ? absoluteUrl(form.attr("action")) : securityUrl;
        var body = new LinkedMultiValueMap<String, String>();
        for (var input : form.select("input[name]")) {
            body.add(input.attr("name"), input.attr("value"));
        }
        body.set("__EVENTTARGET", VALIDATE_EVENT_TARGET);
        body.set("__EVENTARGUMENT", "");
        body.set(turnstileFieldName(form), turnstileToken);
        body.set("cf-turnstile-response", turnstileToken);
        return httpPostForm(action, body, cookieHeader);
    }

    protected ResponseEntity<String> httpPostForm(String url, MultiValueMap<String, String> body, String cookieHeader) {
        return noRedirectClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .headers(headers -> {
                    if (cookieHeader != null && !cookieHeader.isBlank()) {
                        headers.set("Cookie", cookieHeader);
                    }
                })
                .body(body)
                .retrieve()
                .toEntity(String.class);
    }

    private String followIfRedirect(ResponseEntity<String> response, String cookieHeader) {
        if (!response.getStatusCode().is3xxRedirection()) {
            return response.getBody();
        }
        var location = response.getHeaders().getLocation();
        if (location == null) throw new ReceiptParseException("sc-security-redirect-missing-location");
        var first = httpGetResponse(absoluteUrl(location.toString()), cookieHeader);
        if (!first.getStatusCode().is3xxRedirection()) {
            return first.getBody();
        }
        var next = first.getHeaders().getLocation();
        if (next == null) throw new ReceiptParseException("sc-security-final-redirect-missing-location");
        return httpGetResponse(absoluteUrl(next.toString()), cookieHeader).getBody();
    }

    private String securityUrl(String securityHtml, String fallback) {
        var form = Jsoup.parse(securityHtml, BASE_URL).selectFirst("form");
        if (form == null || !form.hasAttr("action")) return fallback;
        return absoluteUrl(form.attr("action"));
    }

    private static String turnstileFieldName(org.jsoup.nodes.Element form) {
        var input = form.selectFirst("input[name$=cf-turnstile-response]");
        return input == null ? TURNSTILE_FALLBACK_FIELD : input.attr("name");
    }

    private static String decodeBase64Text(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            var padded = value + "=".repeat((4 - value.length() % 4) % 4);
            return new String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean isAllowedScUrl(String url) {
        var matcher = URL_HOST.matcher(url);
        if (!matcher.find()) return false;
        var host = matcher.group(2).toLowerCase();
        return host.equals("sef.sc.gov.br")
                || host.endsWith(".sef.sc.gov.br");
    }

    private static String absoluteUrl(String value) {
        if (value == null || value.isBlank()) return BASE_URL;
        if (value.toLowerCase().startsWith("http")) return value;
        return value.startsWith("/") ? BASE_URL + value : BASE_URL + "/" + value;
    }

    private static String encodeScQuerySeparators(String value) {
        return value.replace("|", "%7C");
    }

    private static String extractCookies(List<String> setCookieHeaders) {
        if (setCookieHeaders == null || setCookieHeaders.isEmpty()) return null;
        return setCookieHeaders.stream()
                .map(cookie -> cookie.split(";")[0])
                .collect(Collectors.joining("; "));
    }
}
