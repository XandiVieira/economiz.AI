package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.CaptchaSolveFailedException;
import com.relyon.economizai.exception.CaptchaUnavailableException;
import com.relyon.economizai.exception.InvalidQrPayloadException;
import com.relyon.economizai.exception.SefazFetchException;
import com.relyon.economizai.exception.UnsupportedStateException;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class SefazIngestionService {

    private final Map<UnidadeFederativa, SefazAdapter> adapters;
    private final Optional<InfosimplesService> infosimples;

    public SefazIngestionService(List<SefazAdapter> adapters,
                                 Optional<InfosimplesService> infosimples) {
        var byState = new EnumMap<UnidadeFederativa, SefazAdapter>(UnidadeFederativa.class);
        for (var adapter : adapters) {
            for (var uf : adapter.supportedStates()) {
                var prior = byState.put(uf, adapter);
                if (prior != null && prior != adapter) {
                    throw new IllegalStateException("Two adapters claim " + uf + ": "
                            + prior.getClass().getSimpleName() + " and " + adapter.getClass().getSimpleName());
                }
            }
        }
        this.adapters = Map.copyOf(byState);
        this.infosimples = infosimples;
        log.info("Registered SEFAZ adapters covering UFs: {} infosimples-fallback={}",
                this.adapters.keySet(), infosimples.isPresent());
    }

    public ParsedReceipt ingest(String qrPayload) {
        var fetched = fetch(qrPayload);
        return parse(fetched);
    }

    /**
     * Throws {@link UnsupportedStateException} when no adapter covers the UF.
     * Called synchronously at submit time so users in unsupported states get an
     * immediate localized 400 instead of an async FAILED_PARSE with a raw key.
     */
    public void requireSupported(UnidadeFederativa uf) {
        if (!adapters.containsKey(uf)) {
            throw new UnsupportedStateException(uf.name());
        }
    }

    /**
     * Resolve a QR payload to the 44-digit chave. Most payloads contain it
     * directly; captcha/security wrappers such as SC's SecurityVerify URL need
     * a portal-specific preflight fetch before submit can route by UF.
     */
    public String resolveChave(String qrPayload) {
        try {
            return ChaveAcessoParser.extractChave(qrPayload);
        } catch (InvalidQrPayloadException | CaptchaUnavailableException ex) {
            for (var adapter : adapters.values().stream().distinct().toList()) {
                var resolved = adapter.preflightChave(qrPayload);
                if (resolved.isPresent()) {
                    return resolved.get();
                }
            }
            throw ex;
        }
    }

    /**
     * Step 1: pick the right state adapter, fetch + sanitize the HTML.
     * Split from {@link #parse} so callers (ReceiptService) can persist
     * the raw HTML even when parsing fails downstream — needed by PRO-43.
     *
     * <p>If the primary adapter throws and the Infosimples fallback is enabled,
     * the receipt is fetched via Infosimples instead (paid, ~R$0.24/query).
     * The resulting {@link FetchedDocument} carries a pre-parsed
     * {@link ParsedReceipt} so {@link #parse} returns it directly.
     */
    public FetchedDocument fetch(String qrPayload) {
        var chave = resolveChave(qrPayload);
        var uf = ChaveAcessoParser.extractUf(chave);
        var adapter = adapters.get(uf);
        if (adapter == null) {
            throw new UnsupportedStateException(uf.name());
        }
        try {
            var html = adapter.fetchHtml(qrPayload);
            var sanitized = CpfMasker.strip(html);
            var sourceUrl = qrPayload.trim().toLowerCase().startsWith("http") ? qrPayload.trim() : null;
            return new FetchedDocument(adapter, sanitized, chave, uf, sourceUrl, null);
        } catch (SefazFetchException | CaptchaUnavailableException | CaptchaSolveFailedException primaryEx) {
            // Only failures Infosimples can plausibly rescue: portal down after
            // retries, no solver configured, or the solver itself failed. Every
            // query costs ~R$0.24, so deterministic failures (bad chave, invalid
            // QR, missing sitekey/viewstate) propagate without spending.
            if (infosimples.isEmpty()) throw primaryEx;
            log.warn("sefaz.fetch.primary.failed uf={} chave={} reason={} — trying infosimples fallback",
                    uf, abbrev(chave), primaryEx.getMessage());
            var preParsed = infosimples.get().fetchParsed(chave, uf);
            log.info("sefaz.fetch.infosimples.ok uf={} chave={} items={}",
                    uf, abbrev(chave), preParsed.items().size());
            return new FetchedDocument(null, null, chave, uf, preParsed.sourceUrl(), preParsed);
        }
    }

    /**
     * Step 2: parse the fetched HTML. Returns the pre-parsed receipt immediately
     * when the fetch was served by the Infosimples fallback.
     */
    public ParsedReceipt parse(FetchedDocument fetched) {
        if (fetched.preParsed() != null) return fetched.preParsed();
        return fetched.adapter().parseHtml(fetched.html(), fetched.chave(), fetched.sourceUrl());
    }

    /**
     * Re-runs parsing on already-stored HTML — used by the admin reparse
     * endpoint when a parser fix lands and we want to re-process old
     * receipts without hitting SEFAZ again.
     */
    public ParsedReceipt reparseStored(UnidadeFederativa uf, String html, String chave, String sourceUrl) {
        var adapter = adapters.get(uf);
        if (adapter == null) {
            throw new UnsupportedStateException(uf.name());
        }
        return adapter.parseHtml(html, chave, sourceUrl);
    }

    private static String abbrev(String chave) {
        return chave == null || chave.length() < 8 ? chave : chave.substring(0, 8);
    }

    /**
     * @param adapter    the adapter that produced {@code html}; null for Infosimples fallback results
     * @param html       sanitized DANFE HTML; null for Infosimples fallback results
     * @param preParsed  already-parsed receipt from Infosimples; null for normal HTML-based flow
     */
    public record FetchedDocument(SefazAdapter adapter, String html, String chave,
                                  UnidadeFederativa uf, String sourceUrl, ParsedReceipt preParsed) {
        /** Backward-compatible constructor for existing code and tests. */
        public FetchedDocument(SefazAdapter adapter, String html, String chave,
                               UnidadeFederativa uf, String sourceUrl) {
            this(adapter, html, chave, uf, sourceUrl, null);
        }
    }
}
