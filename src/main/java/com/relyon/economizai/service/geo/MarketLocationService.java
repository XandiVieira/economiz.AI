package com.relyon.economizai.service.geo;

import com.relyon.economizai.model.MarketLocation;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.repository.MarketLocationRepository;
import com.relyon.economizai.service.priceindex.PriceIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manages MarketLocation rows: registers a market entry the first time
 * we see its CNPJ on a confirmed receipt, then geocodes pending entries
 * on a periodic schedule (rate-limited per Nominatim policy).
 *
 * Geocoding is decoupled from the receipt-confirm path so we never
 * block the user on an external API call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketLocationService {

    private static final int MAX_GEOCODE_ATTEMPTS = 3;

    private final MarketLocationRepository repository;
    private final NominatimGeocoder geocoder;

    @Value("${economizai.geo.geocode-delay-ms:1100}")
    private long geocodeDelayMs;

    /** Called from ReceiptService.confirm — idempotent register, never geocodes inline. */
    @Transactional
    public void registerMarketFromReceipt(Receipt receipt) {
        if (receipt.getCnpjEmitente() == null) return;
        var existing = repository.findByCnpj(receipt.getCnpjEmitente());
        if (existing.isPresent()) return;
        var location = MarketLocation.builder()
                .cnpj(receipt.getCnpjEmitente())
                .cnpjRoot(PriceIndexService.cnpjRoot(receipt.getCnpjEmitente()))
                .name(receipt.getMarketName())
                .address(receipt.getMarketAddress())
                .build();
        repository.save(location);
        log.info("market_location.registered cnpj={} name='{}'",
                receipt.getCnpjEmitente(), receipt.getMarketName());
    }

    @Scheduled(fixedDelayString = "${economizai.geo.geocode-interval-ms:600000}",
               initialDelayString = "${economizai.geo.geocode-initial-delay-ms:30000}")
    public void geocodePending() {
        var pending = repository.findAllByLatitudeIsNullAndGeocodeAttemptsLessThan(MAX_GEOCODE_ATTEMPTS);
        if (pending.isEmpty()) return;
        log.info("geocode.batch.start pending={}", pending.size());
        for (var market : pending) {
            geocodeOne(market);
            // Throttle to respect Nominatim's 1 req/sec policy
            try {
                Thread.sleep(geocodeDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.info("geocode.batch.done attempted={}", pending.size());
    }

    @Transactional
    public void geocodeOne(MarketLocation market) {
        market.setGeocodeAttempts(market.getGeocodeAttempts() + 1);
        var query = buildGeocodeQuery(market);
        var result = geocoder.geocode(query);
        if (result.isPresent()) {
            var hit = result.get();
            market.setLatitude(hit.latitude());
            market.setLongitude(hit.longitude());
            market.setCity(hit.city());
            market.setState(hit.state());
            market.setGeocodedAt(LocalDateTime.now());
            market.setGeocodeFailedAt(null);
        } else {
            market.setGeocodeFailedAt(LocalDateTime.now());
        }
        repository.save(market);
    }

    /** Bulk lookup helper for queries that need lat/lng for many CNPJs. */
    @Transactional(readOnly = true)
    public Map<String, MarketLocation> findByCnpjs(List<String> cnpjs) {
        if (cnpjs == null || cnpjs.isEmpty()) return Map.of();
        return repository.findAllByCnpjIn(cnpjs).stream()
                .collect(Collectors.toMap(MarketLocation::getCnpj, m -> m));
    }

    private String buildGeocodeQuery(MarketLocation market) {
        if (market.getAddress() != null && !market.getAddress().isBlank()) {
            return market.getAddress() + ", Brasil";
        }
        if (market.getName() != null && !market.getName().isBlank()) {
            return market.getName() + ", Brasil";
        }
        return "CNPJ " + market.getCnpj() + ", Brasil";
    }
}
