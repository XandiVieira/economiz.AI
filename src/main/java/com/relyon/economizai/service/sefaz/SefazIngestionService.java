package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.CaptchaSolveFailedException;
import com.relyon.economizai.exception.CaptchaUnavailableException;
import com.relyon.economizai.exception.InvalidQrPayloadException;
import com.relyon.economizai.exception.SefazFetchException;
import com.relyon.economizai.exception.UnsupportedStateException;
import com.relyon.economizai.model.enums.PaidApiService;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import com.relyon.economizai.service.paidapi.PaidApiGuardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class SefazIngestionService {

    private final Map<UnidadeFederativa, SefazAdapter> adapters;
    private final Optional<InfosimplesService> infosimples;
    private final PaidApiGuardService paidApiGuard;

    public SefazIngestionService(List<SefazAdapter> adapters,
                                 Optional<InfosimplesService> infosimples,
                                 PaidApiGuardService paidApiGuard) {
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
        this.paidApiGuard = paidApiGuard;
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
        return fetch(qrPayload, null);
    }

    /**
     * As {@link #fetch(String)}, but attributes the paid calls it makes (captcha
     * solves, Infosimples queries) to {@code userId} for the per-user daily cap
     * and the reconciliation ledger. A null {@code userId} (admin reparse, tests)
     * skips the cap but is still logged.
     */
    public FetchedDocument fetch(String qrPayload, UUID userId) {
        var chave = resolveChave(qrPayload);
        var uf = ChaveAcessoParser.extractUf(chave);
        var adapter = adapters.get(uf);
        if (adapter == null) {
            throw new UnsupportedStateException(uf.name());
        }
        // A manually-typed bare chave (damaged QR) has no signature. Portals that
        // need it (SVRS family — PR/SP) can't be scraped, so go straight to the
        // Infosimples by-chave fallback instead of a doomed scrape. RS is rejected
        // up front at submit (gov.br wall), so it doesn't reach here.
        if (adapter.requiresQrSignature() && ChaveAcessoParser.isBareChave(qrPayload)) {
            if (infosimples.isEmpty()) {
                log.warn("sefaz.fetch.bare_chave uf={} chave={} — no by-chave fallback configured", uf, abbrev(chave));
                throw new SefazFetchException(uf.name());
            }
            log.info("sefaz.fetch.bare_chave uf={} chave={} — routing to infosimples (no QR signature to scrape)", uf, abbrev(chave));
            return fetchViaInfosimples(chave, uf, userId);
        }
        // Every scrape solves at least one captcha (paid). Global kill-switch first,
        // then the per-user cap, before spending.
        paidApiGuard.assertUnderGlobalBudget();
        paidApiGuard.assertWithinDailyCap(userId, PaidApiService.CAPTCHA_SOLVE);
        try {
            var html = adapter.fetchHtml(qrPayload);
            paidApiGuard.recordSuccess(userId, PaidApiService.CAPTCHA_SOLVE, uf.name(), null);
            var sanitized = CpfMasker.strip(html);
            var sourceUrl = qrPayload.trim().toLowerCase().startsWith("http") ? qrPayload.trim() : null;
            return new FetchedDocument(adapter, sanitized, chave, uf, sourceUrl, null);
        } catch (SefazFetchException | CaptchaUnavailableException | CaptchaSolveFailedException primaryEx) {
            // A CaptchaUnavailableException means no solver was configured — no money
            // was spent, so don't record a captcha cost. Any other failure here means
            // a solve was attempted (and billed) but the portal still failed.
            if (!(primaryEx instanceof CaptchaUnavailableException)) {
                paidApiGuard.recordFailure(userId, PaidApiService.CAPTCHA_SOLVE, uf.name(), null);
            }
            // Only failures Infosimples can plausibly rescue: portal down after
            // retries, no solver configured, or the solver itself failed. Every
            // query costs ~R$0.24, so deterministic failures (bad chave, invalid
            // QR, missing sitekey/viewstate) propagate without spending.
            if (infosimples.isEmpty()) throw primaryEx;
            log.warn("sefaz.fetch.primary.failed uf={} chave={} reason={} — trying infosimples fallback",
                    uf, abbrev(chave), primaryEx.getMessage());
            return fetchViaInfosimples(chave, uf, userId);
        }
    }

    /**
     * The paid by-chave fallback. Guarded by the Infosimples circuit breaker (skip
     * when the provider is failing) and the per-user daily cap, and every attempt —
     * success or failure — is written to the ledger. A failure trips the breaker.
     */
    private FetchedDocument fetchViaInfosimples(String chave, UnidadeFederativa uf, UUID userId) {
        paidApiGuard.assertUnderGlobalBudget();
        paidApiGuard.assertCircuitClosed(PaidApiService.INFOSIMPLES);
        paidApiGuard.assertWithinDailyCap(userId, PaidApiService.INFOSIMPLES);
        try {
            var preParsed = infosimples.get().fetchParsed(chave, uf);
            paidApiGuard.recordSuccess(userId, PaidApiService.INFOSIMPLES, uf.name(), "infosimples");
            log.info("sefaz.fetch.infosimples.ok uf={} chave={} items={}",
                    uf, abbrev(chave), preParsed.items().size());
            return new FetchedDocument(null, null, chave, uf, preParsed.sourceUrl(), preParsed);
        } catch (RuntimeException ex) {
            paidApiGuard.recordFailure(userId, PaidApiService.INFOSIMPLES, uf.name(), "infosimples");
            throw ex;
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
