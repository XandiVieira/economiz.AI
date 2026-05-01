package com.relyon.economizai.service.geo;

import com.relyon.economizai.dto.response.MarketResponse;
import com.relyon.economizai.exception.MarketNotFoundException;
import com.relyon.economizai.model.MarketLocation;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.UserWatchedMarket;
import com.relyon.economizai.repository.MarketLocationRepository;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.repository.UserWatchedMarketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages the user's "watched markets" list — pinned CNPJs that should
 * surface in price intelligence regardless of distance from home.
 *
 * <p>Markets get into the system organically: every time a household
 * confirms a receipt, {@link MarketLocationService#registerMarketFromReceipt}
 * inserts the CNPJ into {@code market_locations}. Users then pick from
 * that list (filtered to "visited" or "nearby") via the FE.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchedMarketService {

    private final UserWatchedMarketRepository watchedRepository;
    private final MarketLocationRepository marketRepository;
    private final ReceiptRepository receiptRepository;

    /** CNPJs the user is watching. Cheap helper for query-layer joins. */
    @Transactional(readOnly = true)
    public Set<String> watchedCnpjs(User user) {
        return watchedRepository.findAllByUserId(user.getId()).stream()
                .map(UserWatchedMarket::getMarketCnpj)
                .collect(Collectors.toSet());
    }

    /**
     * Just the user's own watchlist, hydrated with location data when we
     * have it. Used by the FE for the "Meus mercados" screen — separate
     * from the picker so we don't pay the picker's joins on every fetch.
     */
    @Transactional(readOnly = true)
    public List<MarketResponse> listWatched(User user) {
        var pinned = watchedRepository.findAllByUserId(user.getId());
        if (pinned.isEmpty()) return List.of();

        var cnpjs = pinned.stream().map(UserWatchedMarket::getMarketCnpj).toList();
        var locations = marketRepository.findAllByCnpjIn(cnpjs).stream()
                .collect(Collectors.toMap(MarketLocation::getCnpj, m -> m));
        var visitedCnpjs = new HashSet<>(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId()));

        return pinned.stream()
                .map(p -> {
                    var loc = locations.get(p.getMarketCnpj());
                    if (loc == null) {
                        return new MarketResponse(p.getMarketCnpj(), null, null, null, null, null, null,
                                visitedCnpjs.contains(p.getMarketCnpj()), true);
                    }
                    return MarketResponse.from(loc, distanceFromHome(user, loc),
                            visitedCnpjs.contains(p.getMarketCnpj()), true);
                })
                .sorted(Comparator
                        .comparing((MarketResponse r) -> r.distanceKm() == null ? Double.MAX_VALUE : r.distanceKm())
                        .thenComparing(r -> r.name() == null ? "" : r.name()))
                .toList();
    }

    /**
     * Catalogue markets for the picker. Combines:
     * <ul>
     *   <li>Markets the household has shopped at (best signal).</li>
     *   <li>Markets the user already watches.</li>
     *   <li>If radiusKm + home set: nearby markets we already have geocoded.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public List<MarketResponse> listForPicker(User user, Double radiusKm) {
        var visitedCnpjs = new HashSet<>(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId()));
        var watchedCnpjs = watchedCnpjs(user);

        var unionCnpjs = new HashSet<String>();
        unionCnpjs.addAll(visitedCnpjs);
        unionCnpjs.addAll(watchedCnpjs);
        var visitedAndWatched = unionCnpjs.isEmpty()
                ? List.<MarketLocation>of()
                : marketRepository.findAllByCnpjIn(List.copyOf(unionCnpjs));

        var nearby = (radiusKm != null && user.getHomeLatitude() != null && user.getHomeLongitude() != null)
                ? marketRepository.findAll().stream()
                        .filter(MarketLocation::hasCoordinates)
                        .filter(m -> !unionCnpjs.contains(m.getCnpj()))
                        .filter(m -> DistanceCalculator.kmBetween(
                                user.getHomeLatitude(), user.getHomeLongitude(),
                                m.getLatitude(), m.getLongitude()) <= radiusKm)
                        .toList()
                : List.<MarketLocation>of();

        return Stream.concat(visitedAndWatched.stream(), nearby.stream())
                .map(m -> MarketResponse.from(
                        m,
                        distanceFromHome(user, m),
                        visitedCnpjs.contains(m.getCnpj()),
                        watchedCnpjs.contains(m.getCnpj())))
                .sorted(Comparator
                        .comparing(MarketResponse::watching).reversed()
                        .thenComparing(MarketResponse::visited, Comparator.reverseOrder())
                        .thenComparing(r -> r.distanceKm() == null ? Double.MAX_VALUE : r.distanceKm()))
                .toList();
    }

    @Transactional
    public MarketResponse watch(User user, String cnpj) {
        var location = marketRepository.findByCnpj(cnpj).orElseThrow(() -> new MarketNotFoundException(cnpj));
        var existing = watchedRepository.findByUserIdAndMarketCnpj(user.getId(), cnpj);
        if (existing.isEmpty()) {
            watchedRepository.save(UserWatchedMarket.builder()
                    .user(user)
                    .marketCnpj(cnpj)
                    .build());
            log.info("watched_market.added user={} cnpj={}", user.getEmail(), cnpj);
        }
        var visited = receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId()).contains(cnpj);
        return MarketResponse.from(location, distanceFromHome(user, location), visited, true);
    }

    @Transactional
    public void unwatch(User user, String cnpj) {
        watchedRepository.deleteByUserIdAndMarketCnpj(user.getId(), cnpj);
        log.info("watched_market.removed user={} cnpj={}", user.getEmail(), cnpj);
    }

    private Double distanceFromHome(User user, MarketLocation location) {
        if (user.getHomeLatitude() == null || user.getHomeLongitude() == null
                || !location.hasCoordinates()) return null;
        return DistanceCalculator.kmBetween(
                user.getHomeLatitude(), user.getHomeLongitude(),
                location.getLatitude(), location.getLongitude());
    }
}
