package com.relyon.economizai.service.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Free geocoder backed by OpenStreetMap Nominatim. Returns a single best-match
 * lat/lng for an address, or empty when nothing is found / the call fails.
 *
 * <p>Nominatim usage policy:
 * <ul>
 *   <li>Max 1 request/second per IP — caller must throttle.</li>
 *   <li>Must set a meaningful User-Agent identifying the application.</li>
 *   <li>Bulk geocoding (&gt; few requests/min) requires self-hosting or a
 *       commercial alternative. We're well below that ceiling for V1.</li>
 * </ul>
 */
@Slf4j
@Service
public class NominatimGeocoder {

    private static final String NOMINATIM_URL = "https://nominatim.openstreetmap.org/search";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NominatimGeocoder(RestClient.Builder builder,
                             @Value("${economizai.geo.user-agent:economizai/0.1 (xandivieira@gmail.com)}") String userAgent,
                             @Value("${economizai.geo.timeout-ms:8000}") int timeoutMs) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = builder
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "application/json")
                .requestFactory(requestFactory)
                .build();
    }

    public Optional<LatLng> geocode(String address) {
        if (address == null || address.isBlank()) return Optional.empty();
        var url = UriComponentsBuilder.fromUriString(NOMINATIM_URL)
                .queryParam("q", address.trim())
                .queryParam("countrycodes", "br")
                .queryParam("format", "json")
                .queryParam("limit", 1)
                .build(true).toUriString();
        log.info("geocode.request address='{}'", address);
        try {
            var body = restClient.get().uri(url).retrieve().body(String.class);
            if (body == null || body.isBlank() || body.equals("[]")) {
                log.info("geocode.empty address='{}'", address);
                return Optional.empty();
            }
            var json = objectMapper.readTree(body);
            if (!json.isArray() || json.size() == 0) return Optional.empty();
            JsonNode first = json.get(0);
            var lat = new BigDecimal(first.get("lat").asText());
            var lon = new BigDecimal(first.get("lon").asText());
            log.info("geocode.ok address='{}' lat={} lon={}", address, lat, lon);
            return Optional.of(new LatLng(lat, lon));
        } catch (Exception ex) {
            log.warn("geocode.failed address='{}' {}: {}", address, ex.getClass().getSimpleName(), ex.getMessage());
            return Optional.empty();
        }
    }

    public record LatLng(BigDecimal latitude, BigDecimal longitude) {}
}
