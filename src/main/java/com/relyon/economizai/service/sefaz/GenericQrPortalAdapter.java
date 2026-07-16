package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.CaptchaUnavailableException;
import com.relyon.economizai.exception.InvalidQrPayloadException;
import com.relyon.economizai.exception.SefazFetchException;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Experimental catch-all adapter for states WITHOUT a verified adapter. Every
 * NFC-e QR code carries the full URL of the emitting state's consult portal,
 * and the majority of portals render the same responsive-DANFE layout the
 * shared parser already understands (the pattern proven on RS, PR, SP and MS —
 * see docs/MULTI_STATE_RECON.md: ~10 UFs are plain server-rendered GETs). So
 * the most likely way to support an unknown state is simply: fetch the QR's
 * own URL and run {@link ResponsiveDanfeParser} on it.
 *
 * <p>{@link SefazIngestionService} assigns this adapter to every UF no
 * dedicated adapter claims (gap-fill, never competing with a verified one) and
 * orchestrates the rest of the chain: Infosimples rescue on fetch/parse
 * failure, per-layer telemetry ({@code state_ingestion_attempts}), and the
 * admin alert when every layer fails. Kill-switch:
 * {@code SEFAZ_EXPERIMENTAL_ENABLED}.
 *
 * <p>SSRF guard: only hosts under the configured suffixes (default
 * {@code gov.br} — every SEFAZ portal lives there) are fetched, so a crafted
 * QR can't point the server at internal or arbitrary hosts.
 */
@Slf4j
@Component
public class GenericQrPortalAdapter implements SefazAdapter {

    private static final Pattern CAPTCHA_MARKER = Pattern.compile(
            "g-recaptcha|recaptcha/api\\.js|hcaptcha|cf-turnstile|challenge-form", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_HOST = Pattern.compile(
            "^(https?)://([^/?#@\\\\]+?)(?::\\d+)?(?=[/?#]|$)", Pattern.CASE_INSENSITIVE);

    private final RestClient restClient;
    private final boolean enabled;
    private final int maxAttempts;
    private final long retryDelayMs;
    private final Set<String> allowedHostSuffixes;

    public GenericQrPortalAdapter(RestClient.Builder builder,
                                  @Value("${economizai.ingestion.sefaz.timeout-ms:30000}") int timeoutMs,
                                  @Value("${economizai.ingestion.sefaz.user-agent:economizai}") String userAgent,
                                  @Value("${economizai.ingestion.sefaz.experimental.enabled:true}") boolean enabled,
                                  @Value("${economizai.ingestion.sefaz.experimental.max-attempts:3}") int maxAttempts,
                                  @Value("${economizai.ingestion.sefaz.retry.delay-ms:5000}") long retryDelayMs,
                                  @Value("${economizai.ingestion.sefaz.experimental.allowed-host-suffixes:gov.br}") String allowedHostSuffixes) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.min(timeoutMs, 10000));
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = builder
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "text/html,application/xhtml+xml")
                .requestFactory(requestFactory)
                .build();
        this.enabled = enabled;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelayMs = Math.max(0, retryDelayMs);
        this.allowedHostSuffixes = parseSuffixes(allowedHostSuffixes);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Claims nothing directly — {@link SefazIngestionService} gap-fills it into
     * every UF left unclaimed by the dedicated adapters.
     */
    @Override
    public Set<UnidadeFederativa> supportedStates() {
        return EnumSet.noneOf(UnidadeFederativa.class);
    }

    /** A bare chave has no portal URL to fetch — route it to the by-chave fallback. */
    @Override
    public boolean requiresQrSignature() {
        return true;
    }

    @Override
    public String fetchHtml(String qrPayload) {
        var url = resolveUrl(qrPayload);
        var uf = ChaveAcessoParser.extractUf(ChaveAcessoParser.extractChave(qrPayload));
        for (var attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return fetchOnce(url, attempt, uf);
            } catch (TransientFetchException ex) {
                if (attempt >= maxAttempts) break;
                log.warn("sefaz.experimental.retry uf={} attempt={}/{} reason={}", uf, attempt, maxAttempts, ex.getMessage());
                sleep(retryDelayMs, uf);
            }
        }
        log.warn("sefaz.experimental.exhausted uf={} attempts={} url={}", uf, maxAttempts, url);
        throw new SefazFetchException(uf.name());
    }

    private String fetchOnce(String url, int attempt, UnidadeFederativa uf) {
        log.info("sefaz.experimental.fetch uf={} attempt={}/{} url={}", uf, attempt, maxAttempts, url);
        try {
            var html = httpGet(url);
            if (html == null || html.isBlank()) {
                throw new TransientFetchException("empty-body");
            }
            if (CAPTCHA_MARKER.matcher(html).find()) {
                // Captcha-walled portal — this adapter can't solve it, but the
                // Infosimples layer can rescue, so signal "rescuable" not "broken".
                log.info("sefaz.experimental.captcha_wall uf={}", uf);
                throw new CaptchaUnavailableException(uf.name());
            }
            log.info("sefaz.experimental.fetch.ok uf={} bytes={}", uf, html.length());
            return html;
        } catch (HttpClientErrorException ex) {
            // 4xx — the portal exists but rejects this consult shape. Deterministic
            // for THIS layer; the chain may still rescue via Infosimples.
            log.warn("sefaz.experimental.client_error uf={} status={}", uf, ex.getStatusCode());
            throw new SefazFetchException(uf.name());
        } catch (RestClientException ex) {
            throw new TransientFetchException(ex.getClass().getSimpleName());
        }
    }

    /** Raw HTTP GET — isolated as a seam so the retry loop is unit-testable. */
    protected String httpGet(String url) {
        return restClient.get().uri(url).retrieve().body(String.class);
    }

    @Override
    public ParsedReceipt parseHtml(String html, String chaveAcesso, String sourceUrl) {
        return ResponsiveDanfeParser.parse(html, chaveAcesso, sourceUrl);
    }

    /**
     * Only full URLs on an allowed host suffix are fetched. Bare chaves never
     * reach here ({@link #requiresQrSignature()} routes them to the fallback),
     * and a QR pointing outside gov.br is treated as invalid, not fetched.
     */
    String resolveUrl(String qrPayload) {
        var trimmed = qrPayload.trim();
        if (!trimmed.toLowerCase().startsWith("http")) {
            throw new InvalidQrPayloadException();
        }
        var matcher = URL_HOST.matcher(trimmed);
        if (!matcher.find()) {
            throw new InvalidQrPayloadException();
        }
        var host = matcher.group(2).toLowerCase();
        var allowed = allowedHostSuffixes.stream()
                .anyMatch(suffix -> host.equals(suffix) || host.endsWith("." + suffix));
        if (!allowed) {
            log.warn("sefaz.experimental.url.rejected host not under allowed suffixes");
            throw new InvalidQrPayloadException();
        }
        return trimmed;
    }

    private void sleep(long ms, UnidadeFederativa uf) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new SefazFetchException(uf.name());
        }
    }

    private Set<String> parseSuffixes(String csv) {
        var suffixes = Arrays.stream(csv == null ? new String[0] : csv.split(","))
                .map(String::trim)
                .filter(suffix -> !suffix.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toUnmodifiableSet());
        return suffixes.isEmpty() ? Set.of("gov.br") : suffixes;
    }

    private static class TransientFetchException extends RuntimeException {
        TransientFetchException(String reason) {
            super(reason);
        }
    }
}
