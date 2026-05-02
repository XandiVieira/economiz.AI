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

    public Optional<GeocodeResult> geocode(String address) {
        if (address == null || address.isBlank()) return Optional.empty();
        var url = UriComponentsBuilder.fromUriString(NOMINATIM_URL)
                .queryParam("q", address.trim())
                .queryParam("countrycodes", "br")
                .queryParam("format", "json")
                .queryParam("addressdetails", 1)
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
            var addr = first.get("address");
            var city = extractCity(addr);
            var state = extractStateAbbreviation(addr);
            log.info("geocode.ok address='{}' lat={} lon={} city={} state={}",
                    address, lat, lon, city, state);
            return Optional.of(new GeocodeResult(lat, lon, city, state));
        } catch (Exception ex) {
            log.warn("geocode.failed address='{}' {}: {}", address, ex.getClass().getSimpleName(), ex.getMessage());
            return Optional.empty();
        }
    }

    private String extractCity(JsonNode addr) {
        if (addr == null) return null;
        // Nominatim returns one of these depending on locality classification
        for (var key : new String[]{"city", "town", "municipality", "village", "city_district"}) {
            var node = addr.get(key);
            if (node != null && !node.asText().isBlank()) return node.asText();
        }
        return null;
    }

    private String extractStateAbbreviation(JsonNode addr) {
        if (addr == null) return null;
        var stateCode = addr.get("ISO3166-2-lvl4");
        if (stateCode != null && stateCode.asText().startsWith("BR-")) {
            return stateCode.asText().substring(3); // "BR-RS" → "RS"
        }
        var stateName = addr.get("state");
        if (stateName != null) return BRAZIL_STATE_BY_NAME.getOrDefault(stateName.asText(), null);
        return null;
    }

    private static final java.util.Map<String, String> BRAZIL_STATE_BY_NAME = java.util.Map.ofEntries(
            java.util.Map.entry("Acre", "AC"), java.util.Map.entry("Alagoas", "AL"),
            java.util.Map.entry("Amapá", "AP"), java.util.Map.entry("Amazonas", "AM"),
            java.util.Map.entry("Bahia", "BA"), java.util.Map.entry("Ceará", "CE"),
            java.util.Map.entry("Distrito Federal", "DF"), java.util.Map.entry("Espírito Santo", "ES"),
            java.util.Map.entry("Goiás", "GO"), java.util.Map.entry("Maranhão", "MA"),
            java.util.Map.entry("Mato Grosso", "MT"), java.util.Map.entry("Mato Grosso do Sul", "MS"),
            java.util.Map.entry("Minas Gerais", "MG"), java.util.Map.entry("Pará", "PA"),
            java.util.Map.entry("Paraíba", "PB"), java.util.Map.entry("Paraná", "PR"),
            java.util.Map.entry("Pernambuco", "PE"), java.util.Map.entry("Piauí", "PI"),
            java.util.Map.entry("Rio de Janeiro", "RJ"), java.util.Map.entry("Rio Grande do Norte", "RN"),
            java.util.Map.entry("Rio Grande do Sul", "RS"), java.util.Map.entry("Rondônia", "RO"),
            java.util.Map.entry("Roraima", "RR"), java.util.Map.entry("Santa Catarina", "SC"),
            java.util.Map.entry("São Paulo", "SP"), java.util.Map.entry("Sergipe", "SE"),
            java.util.Map.entry("Tocantins", "TO")
    );

    public record GeocodeResult(BigDecimal latitude, BigDecimal longitude, String city, String state) {}
}
