package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.UnsupportedStateException;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SefazIngestionService {

    private final Map<UnidadeFederativa, SefazAdapter> adapters;

    public SefazIngestionService(List<SefazAdapter> adapters) {
        this.adapters = adapters.stream()
                .collect(Collectors.toUnmodifiableMap(SefazAdapter::supportedState, Function.identity()));
        log.info("Registered SEFAZ adapters: {}", this.adapters.keySet());
    }

    public ParsedReceipt ingest(String qrPayload) {
        var fetched = fetch(qrPayload);
        return parse(fetched);
    }

    /**
     * Step 1: pick the right state adapter, fetch + sanitize the HTML.
     * Split from {@link #parse} so callers (ReceiptService) can persist
     * the raw HTML even when parsing fails downstream — needed by PRO-43.
     */
    public FetchedDocument fetch(String qrPayload) {
        var chave = ChaveAcessoParser.extractChave(qrPayload);
        var uf = ChaveAcessoParser.extractUf(chave);
        var adapter = adapters.get(uf);
        if (adapter == null) {
            throw new UnsupportedStateException(uf.name());
        }
        var html = adapter.fetchHtml(qrPayload);
        var sanitized = CpfMasker.strip(html);
        var sourceUrl = qrPayload.trim().toLowerCase().startsWith("http") ? qrPayload.trim() : null;
        return new FetchedDocument(adapter, sanitized, chave, uf, sourceUrl);
    }

    public ParsedReceipt parse(FetchedDocument fetched) {
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

    public record FetchedDocument(SefazAdapter adapter, String html, String chave,
                                  UnidadeFederativa uf, String sourceUrl) {}
}
