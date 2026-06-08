package com.relyon.economizai.service.geo;

import com.relyon.economizai.model.MarketLocation;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.enums.CategorizationSource;
import com.relyon.economizai.model.enums.MerchantSegment;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.repository.MarketLocationRepository;
import com.relyon.economizai.repository.ProductRepository;
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
    private static final int MAX_SEGMENT_ATTEMPTS = 3;
    private static final String BRASIL_SUFFIX = ", Brasil";

    private final MarketLocationRepository repository;
    private final NominatimGeocoder geocoder;
    private final CnpjActivityClient cnpjActivityClient;
    private final ProductRepository productRepository;

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

    /**
     * Verifies each unclassified market's business segment from its CNPJ's CNAE.
     * Decoupled from the confirm flow (like geocoding) and best-effort: a lookup
     * failure just leaves the segment UNKNOWN (attempts capped), and ingestion /
     * categorization carry on normally without business-type context. When a
     * market resolves to PHARMACY, OTHER products bought there are backfilled.
     */
    @Scheduled(fixedDelayString = "${economizai.merchant.classify.interval-ms:600000}",
               initialDelayString = "${economizai.merchant.classify.initial-delay-ms:45000}")
    public void scheduledSegmentClassification() {
        classifyPendingSegments();
    }

    /**
     * Classifies every still-UNKNOWN market (attempts capped). Also the
     * admin-triggered backfill entry point. Returns a per-segment summary.
     */
    public SegmentClassificationSummary classifyPendingSegments() {
        if (!cnpjActivityClient.isEnabled()) return new SegmentClassificationSummary(0, 0, 0, 0, 0);
        var pending = repository.findAllBySegmentAndSegmentAttemptsLessThan(
                MerchantSegment.UNKNOWN, MAX_SEGMENT_ATTEMPTS);
        if (pending.isEmpty()) return new SegmentClassificationSummary(0, 0, 0, 0, 0);
        log.info("merchant.classify.batch.start pending={}", pending.size());
        var pharmacy = 0;
        var supermarket = 0;
        var other = 0;
        var unknown = 0;
        for (var market : pending) {
            classifySegmentOne(market);
            switch (market.getSegment()) {
                case PHARMACY -> pharmacy++;
                case SUPERMARKET -> supermarket++;
                case OTHER -> other++;
                case UNKNOWN -> unknown++;
            }
        }
        log.info("merchant.classify.batch.done attempted={} pharmacy={} supermarket={} other={} stillUnknown={}",
                pending.size(), pharmacy, supermarket, other, unknown);
        return new SegmentClassificationSummary(pending.size(), pharmacy, supermarket, other, unknown);
    }

    public record SegmentClassificationSummary(int attempted, int pharmacy, int supermarket,
                                               int other, int stillUnknown) {}

    @Transactional
    public void classifySegmentOne(MarketLocation market) {
        market.setSegmentAttempts(market.getSegmentAttempts() + 1);
        var segment = cnpjActivityClient.classify(market.getCnpj());
        if (segment != MerchantSegment.UNKNOWN) {
            market.setSegment(segment);
            market.setSegmentClassifiedAt(LocalDateTime.now());
            log.info("merchant.classify.ok cnpj={} segment={} name='{}'",
                    market.getCnpj(), segment, market.getName());
        }
        repository.save(market);
        if (segment == MerchantSegment.PHARMACY) {
            backfillPharmacyProducts(market.getCnpj());
        }
    }

    /** Re-tag OTHER products bought at a now-verified pharmacy as PHARMACY. */
    private void backfillPharmacyProducts(String cnpj) {
        var products = productRepository.findOtherCategoryProductsByMerchant(cnpj);
        for (var product : products) {
            product.setCategory(ProductCategory.HEALTH);
            product.setCategorizationSource(CategorizationSource.MERCHANT);
        }
        if (!products.isEmpty()) {
            productRepository.saveAll(products);
            log.info("merchant.classify.backfill cnpj={} products={}", cnpj, products.size());
        }
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
            return market.getAddress() + BRASIL_SUFFIX;
        }
        if (market.getName() != null && !market.getName().isBlank()) {
            return market.getName() + BRASIL_SUFFIX;
        }
        return "CNPJ " + market.getCnpj() + BRASIL_SUFFIX;
    }
}
