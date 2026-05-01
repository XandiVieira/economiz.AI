package com.relyon.economizai.service.priceindex;

import com.relyon.economizai.config.CollaborativeProperties;
import com.relyon.economizai.model.PriceObservation;
import com.relyon.economizai.model.PriceObservationAudit;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.repository.PriceObservationAuditRepository;
import com.relyon.economizai.repository.PriceObservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Owns the collaborative price index (Phase 4).
 *
 * <p>Write path:
 * <ul>
 *   <li>{@link #recordContributions(Receipt)} — called from
 *       ReceiptService.confirm(). Creates one anonymized PriceObservation
 *       per receipt item that has a linked product, plus a private audit row.
 *       Skipped entirely when the user has opted out of contribution
 *       OR the master switch is off.</li>
 * </ul>
 *
 * <p>Read paths (all gated by {@link CollaborativeProperties.Collaborative}
 * env-var thresholds — return empty when data is sparse):
 * <ul>
 *   <li>{@link #referencePrice} — median + min + sample size for a
 *       (product, market) pair. K-anonymity enforced.</li>
 *   <li>{@link #bestMarkets} — markets ranked by median price for a product.
 *       Each row independently k-anon checked.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceIndexService {

    private final PriceObservationRepository observationRepository;
    private final PriceObservationAuditRepository auditRepository;
    private final CollaborativeProperties properties;
    private final com.relyon.economizai.service.geo.MarketLocationService marketLocationService;

    @Transactional
    public int recordContributions(Receipt receipt) {
        if (!properties.getCollaborative().isEnabled()) {
            log.debug("price_index.write.skipped reason=master_switch_off receipt={}", receipt.getId());
            return 0;
        }
        if (!receipt.getUser().isContributionOptIn()) {
            log.info("price_index.write.skipped reason=user_opt_out receipt={}", receipt.getId());
            return 0;
        }
        if (receipt.getCnpjEmitente() == null) {
            log.warn("price_index.write.skipped reason=no_market_cnpj receipt={}", receipt.getId());
            return 0;
        }

        var written = 0;
        for (var item : receipt.getItems()) {
            if (item.getProduct() == null || item.getUnitPrice() == null) continue;
            var observation = PriceObservation.builder()
                    .product(item.getProduct())
                    .marketCnpj(receipt.getCnpjEmitente())
                    .marketCnpjRoot(cnpjRoot(receipt.getCnpjEmitente()))
                    .marketName(receipt.getMarketName())
                    .unitPrice(item.getUnitPrice())
                    .quantity(item.getQuantity())
                    .packSize(item.getProduct().getPackSize())
                    .packUnit(item.getProduct().getPackUnit())
                    .observedAt(receipt.getIssuedAt() != null ? receipt.getIssuedAt() : LocalDateTime.now())
                    .build();
            var saved = observationRepository.save(observation);

            auditRepository.save(PriceObservationAudit.builder()
                    .observation(saved)
                    .receiptId(receipt.getId())
                    .householdId(receipt.getHousehold().getId())
                    .contributedAt(LocalDateTime.now())
                    .build());
            written++;
        }
        log.info("price_index.write.done receipt={} contributed={} marketCnpj={}",
                receipt.getId(), written, receipt.getCnpjEmitente());
        return written;
    }

    @Transactional(readOnly = true)
    public ReferencePrice referencePrice(UUID productId, String marketCnpj) {
        if (!properties.getCollaborative().isEnabled()) return ReferencePrice.empty();
        var since = LocalDateTime.now().minusDays(properties.getCollaborative().getLookbackDays());
        var observations = observationRepository.findRecentByProductAndMarket(productId, marketCnpj, since);
        if (observations.size() < properties.getCollaborative().getMinObservationsPerProductMarket()) {
            log.debug("reference_price.empty reason=insufficient_observations product={} market={} have={} need={}",
                    productId, marketCnpj, observations.size(),
                    properties.getCollaborative().getMinObservationsPerProductMarket());
            return ReferencePrice.empty();
        }
        var distinctHouseholds = auditRepository.countDistinctHouseholdsForProductMarket(productId, marketCnpj, since);
        if (distinctHouseholds < properties.getCollaborative().getMinHouseholdsForPublic()) {
            log.debug("reference_price.empty reason=k_anon product={} market={} households={} need={}",
                    productId, marketCnpj, distinctHouseholds,
                    properties.getCollaborative().getMinHouseholdsForPublic());
            return ReferencePrice.empty();
        }
        var prices = observations.stream().map(PriceObservation::getUnitPrice).toList();
        return new ReferencePrice(median(prices), min(prices), max(prices),
                observations.size(), distinctHouseholds, observations.get(0).getObservedAt());
    }

    @Transactional(readOnly = true)
    public List<MarketPriceRow> bestMarkets(UUID productId, int limit,
                                            BigDecimal userLatitude, BigDecimal userLongitude, Double radiusKm) {
        if (!properties.getCollaborative().isEnabled()) return List.of();
        var since = LocalDateTime.now().minusDays(properties.getCollaborative().getLookbackDays());
        var observations = observationRepository.findRecentByProduct(productId, since);
        if (observations.isEmpty()) return List.of();

        var byMarket = observations.stream()
                .collect(java.util.stream.Collectors.groupingBy(PriceObservation::getMarketCnpj));
        var locations = marketLocationService.findByCnpjs(new java.util.ArrayList<>(byMarket.keySet()));

        return byMarket.entrySet().stream()
                .map(entry -> {
                    var cnpj = entry.getKey();
                    var rows = entry.getValue();
                    if (rows.size() < properties.getCollaborative().getMinObservationsPerProductMarket()) return null;
                    var distinct = auditRepository.countDistinctHouseholdsForProductMarket(productId, cnpj, since);
                    if (distinct < properties.getCollaborative().getMinHouseholdsForPublic()) return null;
                    var location = locations.get(cnpj);
                    Double distanceKm = null;
                    if (userLatitude != null && userLongitude != null && location != null && location.hasCoordinates()) {
                        distanceKm = com.relyon.economizai.service.geo.DistanceCalculator.kmBetween(
                                userLatitude, userLongitude, location.getLatitude(), location.getLongitude());
                        if (radiusKm != null && distanceKm > radiusKm) return null;
                    } else if (radiusKm != null && userLatitude != null) {
                        // user wanted a radius filter but we don't have this market's coords yet → exclude
                        return null;
                    }
                    var prices = rows.stream().map(PriceObservation::getUnitPrice).toList();
                    return new MarketPriceRow(cnpj, cnpjRoot(cnpj), rows.get(0).getMarketName(),
                            median(prices), min(prices), rows.size(), distinct, distanceKm);
                })
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(MarketPriceRow::medianPrice))
                .limit(limit)
                .toList();
    }

    /** Median (50th percentile) of a price list. Returns null on empty. */
    static BigDecimal median(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) return null;
        var sorted = values.stream().sorted().toList();
        var n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return sorted.get(n / 2 - 1).add(sorted.get(n / 2))
                .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }

    static BigDecimal min(List<BigDecimal> values) {
        return values.stream().min(BigDecimal::compareTo).orElse(null);
    }

    static BigDecimal max(List<BigDecimal> values) {
        return values.stream().max(BigDecimal::compareTo).orElse(null);
    }

    public static String cnpjRoot(String cnpj) {
        if (cnpj == null || cnpj.length() < 8) return cnpj;
        return cnpj.substring(0, 8);
    }

    public record ReferencePrice(BigDecimal medianPrice, BigDecimal minPrice, BigDecimal maxPrice,
                                 int sampleCount, long distinctHouseholds, LocalDateTime mostRecentAt) {
        public static ReferencePrice empty() {
            return new ReferencePrice(null, null, null, 0, 0, null);
        }
        public boolean hasData() { return medianPrice != null; }
    }

    public record MarketPriceRow(String cnpj, String cnpjRoot, String marketName,
                                 BigDecimal medianPrice, BigDecimal minPrice,
                                 int sampleCount, long distinctHouseholds,
                                 Double distanceKm) {}
}
