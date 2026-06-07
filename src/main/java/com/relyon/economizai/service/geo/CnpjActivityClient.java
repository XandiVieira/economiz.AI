package com.relyon.economizai.service.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizai.model.enums.MerchantSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Looks up a CNPJ's business segment from its CNAE (economic activity) via the
 * free, no-auth BrasilAPI registry. Best-effort and resilient: transient
 * failures are retried a few times, and any unrecoverable failure returns
 * {@link MerchantSegment#UNKNOWN} rather than throwing — classification is an
 * enrichment, never a hard requirement on the ingestion flow.
 *
 * <p>CNAE → segment: {@code 4771*} = pharmacy, {@code 4711*}/{@code 4712*} =
 * supermarket. Checks the primary CNAE first, then the secondary list.
 */
@Slf4j
@Service
public class CnpjActivityClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final int maxAttempts;
    private final boolean enabled;

    public CnpjActivityClient(RestClient.Builder builder,
                              @Value("${economizai.merchant.classify.base-url:https://brasilapi.com.br/api/cnpj/v1}") String baseUrl,
                              @Value("${economizai.merchant.classify.timeout-ms:8000}") int timeoutMs,
                              @Value("${economizai.merchant.classify.max-attempts:3}") int maxAttempts,
                              @Value("${economizai.merchant.classify.user-agent:economizai}") String userAgent,
                              @Value("${economizai.merchant.classify.enabled:true}") boolean enabled) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.min(timeoutMs, 5000));
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = builder
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "application/json")
                .requestFactory(requestFactory)
                .build();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Classifies the CNPJ's segment. Returns UNKNOWN on disabled, bad input, or
     * after exhausting retries — callers treat UNKNOWN as "couldn't verify, carry
     * on without business-type context".
     */
    public MerchantSegment classify(String cnpj) {
        if (!enabled || cnpj == null || cnpj.isBlank()) return MerchantSegment.UNKNOWN;
        var body = fetchWithRetry(cnpj);
        if (body == null) return MerchantSegment.UNKNOWN;
        try {
            return segmentFromCnae(parseCnaes(body));
        } catch (Exception ex) {
            log.warn("merchant.classify.parse_failed cnpj={} {}: {}", cnpj, ex.getClass().getSimpleName(), ex.getMessage());
            return MerchantSegment.UNKNOWN;
        }
    }

    private String fetchWithRetry(String cnpj) {
        var url = baseUrl + "/" + cnpj;
        for (var attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return restClient.get().uri(url).retrieve().body(String.class);
            } catch (Exception ex) {
                log.warn("merchant.classify.fetch_failed attempt={}/{} cnpj={} {}",
                        attempt, maxAttempts, cnpj, ex.getClass().getSimpleName());
            }
        }
        return null;
    }

    private List<String> parseCnaes(String body) throws Exception {
        var json = objectMapper.readTree(body);
        var codes = new ArrayList<String>();
        var primary = json.get("cnae_fiscal");
        if (primary != null && !primary.isNull()) codes.add(primary.asText());
        var secundarios = json.get("cnaes_secundarios");
        if (secundarios != null && secundarios.isArray()) {
            for (JsonNode node : secundarios) {
                var code = node.get("codigo");
                if (code != null && !code.isNull()) codes.add(code.asText());
            }
        }
        return codes;
    }

    /** Pure mapping (CNAE prefixes → segment), extracted for testability. */
    static MerchantSegment segmentFromCnae(List<String> cnaes) {
        if (cnaes.stream().anyMatch(code -> code.startsWith("4771"))) return MerchantSegment.PHARMACY;
        if (cnaes.stream().anyMatch(code -> code.startsWith("4711") || code.startsWith("4712"))) {
            return MerchantSegment.SUPERMARKET;
        }
        return cnaes.isEmpty() ? MerchantSegment.UNKNOWN : MerchantSegment.OTHER;
    }
}
