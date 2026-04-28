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
        var chave = ChaveAcessoParser.extractChave(qrPayload);
        var uf = ChaveAcessoParser.extractUf(chave);
        var adapter = adapters.get(uf);
        if (adapter == null) {
            throw new UnsupportedStateException(uf.name());
        }
        var html = adapter.fetchHtml(qrPayload);
        var sanitized = CpfMasker.strip(html);
        var sourceUrl = qrPayload.trim().toLowerCase().startsWith("http") ? qrPayload.trim() : null;
        return adapter.parseHtml(sanitized, chave, sourceUrl);
    }
}
