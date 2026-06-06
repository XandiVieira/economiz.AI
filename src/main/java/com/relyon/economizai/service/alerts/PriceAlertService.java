package com.relyon.economizai.service.alerts;

import com.relyon.economizai.dto.request.CreatePriceAlertRequest;
import com.relyon.economizai.dto.response.PriceAlertResponse;
import com.relyon.economizai.exception.PriceAlertNotFoundException;
import com.relyon.economizai.exception.ProductNotFoundException;
import com.relyon.economizai.model.MarketLocation;
import com.relyon.economizai.model.PriceAlert;
import com.relyon.economizai.model.PriceObservation;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.NotificationType;
import com.relyon.economizai.repository.PriceAlertRepository;
import com.relyon.economizai.repository.ProductRepository;
import com.relyon.economizai.service.geo.DistanceCalculator;
import com.relyon.economizai.service.geo.MarketLocationService;
import com.relyon.economizai.service.notifications.NotificationPayload;
import com.relyon.economizai.service.notifications.NotificationService;
import com.relyon.economizai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "Avise-me quando" price-drop alerts.
 *
 * <p>CRUD for the user's rules, plus {@link #evaluate} — invoked on the
 * collaborative-index write path so that one household's confirmed receipt
 * can fire another household's alert. This is the community retention loop
 * described in HELP.md (Tier 1 #3).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceAlertService {

    /** Don't fire the same rule more than once within this window. */
    private static final Duration COOLDOWN = Duration.ofHours(24);

    private final PriceAlertRepository alertRepository;
    private final ProductRepository productRepository;
    private final MarketLocationService marketLocationService;
    private final NotificationService notificationService;

    @Transactional
    public PriceAlertResponse create(User user, CreatePriceAlertRequest request) {
        var product = productRepository.findById(request.productId())
                .orElseThrow(ProductNotFoundException::new);
        var active = request.active() == null || request.active();

        var alert = alertRepository.findByUserIdAndProductId(user.getId(), product.getId())
                .map(existing -> {
                    existing.setThresholdPrice(request.thresholdPrice());
                    existing.setRadiusKm(request.radiusKm());
                    existing.setActive(active);
                    return existing;
                })
                .orElseGet(() -> PriceAlert.builder()
                        .user(user)
                        .product(product)
                        .thresholdPrice(request.thresholdPrice())
                        .radiusKm(request.radiusKm())
                        .active(active)
                        .build());
        var saved = alertRepository.save(alert);
        log.info("price_alert.saved user={} product={} threshold={} radiusKm={}",
                LogMasker.email(user.getEmail()), product.getId(), saved.getThresholdPrice(), saved.getRadiusKm());
        return PriceAlertResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<PriceAlertResponse> list(User user) {
        return alertRepository.findAllByUserIdFetchProduct(user.getId()).stream()
                .map(PriceAlertResponse::from)
                .toList();
    }

    @Transactional
    public void delete(User user, UUID id) {
        var alert = alertRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(PriceAlertNotFoundException::new);
        alertRepository.delete(alert);
        log.info("price_alert.deleted user={} alert={}", LogMasker.email(user.getEmail()), id);
    }

    /**
     * Match freshly-written observations against every active rule and notify
     * the owners whose threshold (and optional radius) is met. Skips rules
     * owned by the contributing household (don't ping someone about their own
     * receipt) and rules still inside their cooldown window.
     */
    @Transactional
    public void evaluate(List<PriceObservation> observations, UUID contributingHouseholdId) {
        if (observations == null || observations.isEmpty()) return;

        var productIds = observations.stream()
                .map(o -> o.getProduct().getId())
                .collect(Collectors.toSet());
        var alertsByProduct = alertRepository.findActiveByProductIdInFetchUser(productIds).stream()
                .collect(Collectors.groupingBy(a -> a.getProduct().getId()));
        if (alertsByProduct.isEmpty()) return;

        var cnpjs = observations.stream().map(PriceObservation::getMarketCnpj).distinct().toList();
        var locations = marketLocationService.findByCnpjs(cnpjs);
        var now = LocalDateTime.now();
        var firedAlertIds = new HashSet<UUID>();
        var fired = 0;

        for (var observation : observations) {
            var alerts = alertsByProduct.get(observation.getProduct().getId());
            if (alerts == null) continue;
            for (var alert : alerts) {
                if (firedAlertIds.contains(alert.getId())) continue;
                if (sameHousehold(alert, contributingHouseholdId)) continue;
                if (observation.getUnitPrice().compareTo(alert.getThresholdPrice()) > 0) continue;
                if (inCooldown(alert, now)) continue;
                if (!withinRadius(alert, observation, locations)) continue;

                notify(alert, observation);
                alert.setLastFiredAt(now);
                firedAlertIds.add(alert.getId());
                fired++;
            }
        }
        if (fired > 0) {
            alertRepository.saveAll(collectFired(alertsByProduct, firedAlertIds));
            log.info("price_alert.evaluate done observations={} fired={}", observations.size(), fired);
        }
    }

    private boolean sameHousehold(PriceAlert alert, UUID contributingHouseholdId) {
        return contributingHouseholdId != null
                && alert.getUser().getHousehold() != null
                && contributingHouseholdId.equals(alert.getUser().getHousehold().getId());
    }

    private boolean inCooldown(PriceAlert alert, LocalDateTime now) {
        return alert.getLastFiredAt() != null
                && alert.getLastFiredAt().isAfter(now.minus(COOLDOWN));
    }

    private boolean withinRadius(PriceAlert alert, PriceObservation observation,
                                 Map<String, MarketLocation> locations) {
        if (alert.getRadiusKm() == null) return true;
        var user = alert.getUser();
        var location = locations.get(observation.getMarketCnpj());
        if (user.getHomeLatitude() == null || user.getHomeLongitude() == null
                || location == null || !location.hasCoordinates()) {
            return false; // radius requested but we can't place it — honor the constraint
        }
        var km = DistanceCalculator.kmBetween(
                user.getHomeLatitude(), user.getHomeLongitude(),
                location.getLatitude(), location.getLongitude());
        return km <= alert.getRadiusKm();
    }

    private void notify(PriceAlert alert, PriceObservation observation) {
        var productName = observation.getProduct().getNormalizedName();
        var title = "Preço baixou: " + productName;
        var body = String.format(
                "%s saiu por R$ %s no %s. Você pediu para avisar abaixo de R$ %s.",
                productName, observation.getUnitPrice(),
                observation.getMarketName() != null ? observation.getMarketName() : "um mercado próximo",
                alert.getThresholdPrice());
        notificationService.notify(new NotificationPayload(
                alert.getUser(),
                NotificationType.PRICE_DROP,
                title, body,
                Map.of(
                        "alertId", alert.getId().toString(),
                        "productId", observation.getProduct().getId().toString(),
                        "observedPrice", observation.getUnitPrice(),
                        "thresholdPrice", alert.getThresholdPrice(),
                        "marketCnpj", observation.getMarketCnpj(),
                        "marketName", observation.getMarketName() != null ? observation.getMarketName() : ""
                )));
    }

    private List<PriceAlert> collectFired(Map<UUID, List<PriceAlert>> alertsByProduct, HashSet<UUID> firedIds) {
        return alertsByProduct.values().stream()
                .flatMap(List::stream)
                .filter(a -> firedIds.contains(a.getId()))
                .distinct()
                .toList();
    }
}
