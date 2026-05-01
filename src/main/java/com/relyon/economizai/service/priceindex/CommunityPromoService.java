package com.relyon.economizai.service.priceindex;

import com.relyon.economizai.config.CollaborativeProperties;
import com.relyon.economizai.model.MarketLocation;
import com.relyon.economizai.model.PriceObservation;
import com.relyon.economizai.repository.PriceObservationAuditRepository;
import com.relyon.economizai.repository.PriceObservationRepository;
import com.relyon.economizai.service.geo.DistanceCalculator;
import com.relyon.economizai.service.geo.MarketLocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Community-wide promo detection.
 *
 * <p>Algorithm per (product, market):
 * <ol>
 *   <li>Load all observations for product in the last
 *       {@link CollaborativeProperties.Collaborative#getLookbackDays()} days.</li>
 *   <li>Split into "recent" (last 7 days) and "baseline" (the rest).</li>
 *   <li>If recent median is more than
 *       {@link CollaborativeProperties.Collaborative#getCommunityPromoThresholdPct()}%
 *       below baseline median AND both samples meet k-anonymity,
 *       it's a community promo.</li>
 * </ol>
 *
 * <p>Stateless / on-demand for V1 (no community_promos table yet — added
 * later when notifications need to react to promo events).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityPromoService {

    private static final int RECENT_WINDOW_DAYS = 7;

    private final PriceObservationRepository observationRepository;
    private final PriceObservationAuditRepository auditRepository;
    private final CollaborativeProperties properties;
    private final MarketLocationService marketLocationService;

    /** Backwards-compat overload used by tests + scheduled jobs (no distance filter). */
    public List<CommunityPromo> detectAll() {
        return detectAll(null, null, null, Set.of());
    }

    @Transactional(readOnly = true)
    public List<CommunityPromo> detectAll(BigDecimal userLatitude,
                                          BigDecimal userLongitude,
                                          Double radiusKm,
                                          Set<String> watchedCnpjs) {
        if (!properties.getCollaborative().isEnabled()) return List.of();
        var watched = watchedCnpjs == null ? Set.<String>of() : watchedCnpjs;

        var since = LocalDateTime.now().minusDays(properties.getCollaborative().getLookbackDays());
        var observations = observationRepository.findRecent(since);
        if (observations.isEmpty()) return List.of();

        var locations = (userLatitude != null && userLongitude != null)
                ? marketLocationService.findByCnpjs(observations.stream()
                        .map(PriceObservation::getMarketCnpj).distinct().toList())
                : Map.<String, MarketLocation>of();

        // Group by (productId, marketCnpj)
        Map<UUID, Map<String, List<PriceObservation>>> byProductMarket = observations.stream()
                .collect(Collectors.groupingBy(
                        po -> po.getProduct().getId(),
                        Collectors.groupingBy(PriceObservation::getMarketCnpj)));

        var promos = new ArrayList<CommunityPromo>();
        var recentCutoff = LocalDateTime.now().minusDays(RECENT_WINDOW_DAYS);

        for (var productEntry : byProductMarket.entrySet()) {
            var productId = productEntry.getKey();
            for (var marketEntry : productEntry.getValue().entrySet()) {
                var marketCnpj = marketEntry.getKey();
                var rows = marketEntry.getValue();
                if (rows.size() < properties.getCollaborative().getMinObservationsForCommunityPromo()) continue;

                var distinctHouseholds = auditRepository.countDistinctHouseholdsForProductMarket(productId, marketCnpj, since);
                if (distinctHouseholds < properties.getCollaborative().getMinHouseholdsForPublic()) continue;

                Double distanceKm = null;
                var isWatched = watched.contains(marketCnpj);
                if (userLatitude != null && userLongitude != null) {
                    var location = locations.get(marketCnpj);
                    if (location == null || !location.hasCoordinates()) {
                        if (radiusKm != null && !isWatched) continue; // user wants radius filter but no coords
                    } else {
                        distanceKm = DistanceCalculator.kmBetween(
                                userLatitude, userLongitude, location.getLatitude(), location.getLongitude());
                        if (radiusKm != null && distanceKm > radiusKm && !isWatched) continue;
                    }
                }

                var recentPrices = rows.stream()
                        .filter(po -> po.getObservedAt().isAfter(recentCutoff))
                        .map(PriceObservation::getUnitPrice)
                        .toList();
                var baselinePrices = rows.stream()
                        .filter(po -> !po.getObservedAt().isAfter(recentCutoff))
                        .map(PriceObservation::getUnitPrice)
                        .toList();
                if (recentPrices.isEmpty() || baselinePrices.size() < 3) continue;

                var recentMedian = PriceIndexService.median(recentPrices);
                var baselineMedian = PriceIndexService.median(baselinePrices);
                var threshold = baselineMedian
                        .multiply(BigDecimal.valueOf(100 - properties.getCollaborative().getCommunityPromoThresholdPct()))
                        .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);

                if (recentMedian.compareTo(threshold) < 0) {
                    var dropPct = baselineMedian.subtract(recentMedian)
                            .multiply(BigDecimal.valueOf(100))
                            .divide(baselineMedian, 2, RoundingMode.HALF_UP);
                    var firstRow = rows.get(0);
                    var promo = new CommunityPromo(
                            productId,
                            firstRow.getProduct().getNormalizedName(),
                            marketCnpj,
                            PriceIndexService.cnpjRoot(marketCnpj),
                            firstRow.getMarketName(),
                            recentMedian,
                            baselineMedian,
                            dropPct,
                            recentPrices.size(),
                            distinctHouseholds,
                            distanceKm,
                            isWatched
                    );
                    promos.add(promo);
                    log.info("community_promo.detected product={} market={} recent={} baseline={} dropPct={} samples={} households={}",
                            productId, marketCnpj, recentMedian, baselineMedian, dropPct, recentPrices.size(), distinctHouseholds);
                }
            }
        }
        promos.sort((a, b) -> b.dropPct().compareTo(a.dropPct()));
        return promos;
    }

    public record CommunityPromo(
            UUID productId,
            String productName,
            String marketCnpj,
            String marketCnpjRoot,
            String marketName,
            BigDecimal currentMedianPrice,
            BigDecimal baselineMedianPrice,
            BigDecimal dropPct,
            int recentSampleCount,
            long distinctHouseholds,
            Double distanceKm,
            boolean watching
    ) {}
}
