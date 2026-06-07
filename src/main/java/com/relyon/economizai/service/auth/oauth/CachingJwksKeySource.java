package com.relyon.economizai.service.auth.oauth;

import com.nimbusds.jose.jwk.JWKSet;
import com.relyon.economizai.exception.InvalidOAuthTokenException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches provider JWKS over HTTP and caches the parsed key set in memory,
 * refreshing periodically. Google and Apple rotate their signing keys, so the
 * cache has a short TTL; a parse-time key miss is the verifier's signal to
 * tolerate rotation on its next attempt.
 */
@Slf4j
@Component
public class CachingJwksKeySource implements JwksKeySource {

    private static final Duration TTL = Duration.ofHours(1);

    private final RestClient restClient;
    private final ConcurrentHashMap<String, CachedKeySet> cache = new ConcurrentHashMap<>();

    public CachingJwksKeySource(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    @Override
    public JWKSet keySet(String jwksUrl) {
        var cached = cache.get(jwksUrl);
        if (cached != null && !cached.isExpired()) {
            return cached.keySet();
        }
        var fetched = fetch(jwksUrl);
        cache.put(jwksUrl, new CachedKeySet(fetched, Instant.now().plus(TTL)));
        return fetched;
    }

    private JWKSet fetch(String jwksUrl) {
        try {
            var body = restClient.get().uri(jwksUrl).retrieve().body(String.class);
            log.debug("oauth.jwks.fetched url={}", jwksUrl);
            return JWKSet.parse(body);
        } catch (Exception ex) {
            log.warn("oauth.jwks.fetch_failed url={} {}: {}", jwksUrl, ex.getClass().getSimpleName(), ex.getMessage());
            throw new InvalidOAuthTokenException();
        }
    }

    private record CachedKeySet(JWKSet keySet, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
