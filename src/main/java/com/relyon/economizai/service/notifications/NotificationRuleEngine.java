package com.relyon.economizai.service.notifications;

import com.relyon.economizai.model.MarketLocation;
import com.relyon.economizai.model.NotificationRule;
import com.relyon.economizai.model.PriceObservation;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.NotificationType;
import com.relyon.economizai.repository.NotificationRuleRepository;
import com.relyon.economizai.repository.ReceiptItemRepository;
import com.relyon.economizai.repository.UserWatchedMarketRepository;
import com.relyon.economizai.service.geo.DistanceCalculator;
import com.relyon.economizai.service.geo.MarketLocationService;
import com.relyon.economizai.service.geo.MarketNameService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Evaluates freshly-written {@link PriceObservation}s against the rules that
 * react to community prices — runs on the collaborative-index write path so one
 * household's confirmed receipt can fire another household's notification.
 *
 * <p>Handles:
 * <ul>
 *   <li><b>PRICE_DROP</b> — user rule, fire when observed price ≤ threshold.</li>
 *   <li><b>PRICE_ABOVE</b> — user rule, fire when observed price ≥ threshold.</li>
 *   <li><b>CHEAPER_MARKET</b> — default, a product you've bought is observed at one of
 *       your <em>watched markets</em> (and, if you opt in via the rule's radius, at any
 *       market near home) at least {@link #DROP_FRACTION} below your last paid price.</li>
 *   <li><b>PROMO_COMMUNITY</b> — default, a product you've bought is flagged as a promo anywhere.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationRuleEngine {

    /** Don't fire the same rule more than once within this window. */
    private static final Duration COOLDOWN = Duration.ofHours(24);

    // Cheaper-market default: the required drop scales with price. A 20% cut on a
    // R$1 item matters; on a R$200 item even 5% is real money. We interpolate the
    // required drop on log-price between the anchors below, clamped to [MIN, MAX].
    private static final double MAX_DROP = 0.20;                          // cheap items need a deep cut
    private static final double MIN_DROP = 0.05;                          // pricey items: small % still matters
    private static final BigDecimal LOW_PRICE_ANCHOR = new BigDecimal("1");    // R$1  -> 20% required
    private static final BigDecimal HIGH_PRICE_ANCHOR = new BigDecimal("200"); // R$200 -> 5% required

    private final NotificationRuleRepository ruleRepository;
    private final ReceiptItemRepository receiptItemRepository;
    private final UserWatchedMarketRepository watchedMarketRepository;
    private final MarketLocationService marketLocationService;
    private final MarketNameService marketNameService;
    private final NotificationService notificationService;

    @Transactional
    public void evaluate(List<PriceObservation> observations, UUID contributingHouseholdId) {
        if (observations == null || observations.isEmpty()) return;

        var productIds = observations.stream().map(o -> o.getProduct().getId()).collect(Collectors.toSet());
        var cnpjs = observations.stream().map(PriceObservation::getMarketCnpj).distinct().toList();
        var locations = marketLocationService.findByCnpjs(cnpjs);
        var now = LocalDateTime.now();

        var fired = new ArrayList<NotificationRule>();
        fired.addAll(evaluateUserPriceRules(observations, productIds, locations, contributingHouseholdId, now));
        fired.addAll(evaluateCommunityDefaults(observations, locations, contributingHouseholdId, now));

        if (!fired.isEmpty()) {
            ruleRepository.saveAll(fired);
            log.info("notification_rule.evaluate done observations={} fired={}", observations.size(), fired.size());
        }
    }

    // ---- user price rules: PRICE_DROP (<=) and PRICE_ABOVE (>=) ----

    private List<NotificationRule> evaluateUserPriceRules(List<PriceObservation> observations,
                                                          Set<UUID> productIds,
                                                          Map<String, MarketLocation> locations,
                                                          UUID contributingHouseholdId,
                                                          LocalDateTime now) {
        var rules = ruleRepository.findActiveProductRules(
                List.of(NotificationType.PRICE_DROP, NotificationType.PRICE_ABOVE), productIds);
        if (rules.isEmpty()) return List.of();

        var rulesByProduct = rules.stream().collect(Collectors.groupingBy(rule -> rule.getProduct().getId()));
        var firedIds = new HashSet<UUID>();
        var fired = new ArrayList<NotificationRule>();

        for (var observation : observations) {
            var candidates = rulesByProduct.get(observation.getProduct().getId());
            if (candidates == null) continue;
            for (var rule : candidates) {
                if (firedIds.contains(rule.getId())) continue;
                if (sameHousehold(rule, contributingHouseholdId)) continue;
                if (!thresholdMet(rule, observation.getUnitPrice())) continue;
                if (inCooldown(rule, now)) continue;
                if (!withinRadius(rule.getRadiusKm(), rule.getUser(), observation, locations)) continue;

                notifyPriceRule(rule, observation);
                rule.setLastFiredAt(now);
                firedIds.add(rule.getId());
                fired.add(rule);
            }
        }
        return fired;
    }

    private boolean thresholdMet(NotificationRule rule, BigDecimal unitPrice) {
        var threshold = rule.getThresholdPrice();
        if (threshold == null) return false;
        return rule.getType() == NotificationType.PRICE_ABOVE
                ? unitPrice.compareTo(threshold) >= 0
                : unitPrice.compareTo(threshold) <= 0;
    }

    private void notifyPriceRule(NotificationRule rule, PriceObservation observation) {
        var productName = observation.getProduct().getNormalizedName();
        var marketName = friendlyMarketName(rule, observation);
        String title;
        String body;
        if (rule.getType() == NotificationType.PRICE_ABOVE) {
            title = "Preço subiu: " + productName;
            body = String.format("%s está R$ %s no %s — acima do teto de R$ %s que você definiu.",
                    productName, observation.getUnitPrice(), marketName, rule.getThresholdPrice());
        } else {
            title = "Preço baixou: " + productName;
            body = String.format("%s saiu por R$ %s no %s. Você pediu para avisar abaixo de R$ %s.",
                    productName, observation.getUnitPrice(), marketName, rule.getThresholdPrice());
        }
        notificationService.notify(new NotificationPayload(
                rule.getUser(), rule.getType(), title, body, baseExtras(rule, observation)));
    }

    // ---- community defaults: PROMO_COMMUNITY (on sale) and CHEAPER_MARKET (cheaper at your markets) ----

    private List<NotificationRule> evaluateCommunityDefaults(List<PriceObservation> observations,
                                                             Map<String, MarketLocation> locations,
                                                             UUID contributingHouseholdId,
                                                             LocalDateTime now) {
        var byProduct = observations.stream().collect(Collectors.groupingBy(o -> o.getProduct().getId()));
        // Shared across products: a user's single default rule must fire at most once
        // even when they bought several products on the same receipt (the cooldown isn't
        // persisted within this evaluation, so we dedup by rule id in-memory).
        var firedRuleIds = new HashSet<UUID>();
        var fired = new ArrayList<NotificationRule>();
        for (var entry : byProduct.entrySet()) {
            fired.addAll(firePromoCommunity(entry.getKey(), entry.getValue(),
                    contributingHouseholdId, now, firedRuleIds));
            fired.addAll(fireCheaperMarket(entry.getKey(), entry.getValue(), locations,
                    contributingHouseholdId, now, firedRuleIds));
        }
        return fired;
    }

    private List<NotificationRule> firePromoCommunity(UUID productId, List<PriceObservation> observations,
                                                      UUID contributingHouseholdId, LocalDateTime now,
                                                      Set<UUID> firedRuleIds) {
        var promo = observations.stream()
                .filter(PriceObservation::isPromoFlag)
                .min(Comparator.comparing(PriceObservation::getUnitPrice))
                .orElse(null);
        if (promo == null) return List.of();

        var owners = ruleRepository.findActiveDefaultRuleOwnersWhoBought(NotificationType.PROMO_COMMUNITY, productId);
        var fired = new ArrayList<NotificationRule>();
        for (var rule : owners) {
            if (firedRuleIds.contains(rule.getId())) continue;
            if (sameHousehold(rule, contributingHouseholdId) || inCooldown(rule, now)) continue;
            var productName = promo.getProduct().getNormalizedName();
            notificationService.notify(new NotificationPayload(
                    rule.getUser(), NotificationType.PROMO_COMMUNITY,
                    "Oferta na comunidade: " + productName,
                    String.format("%s está em promoção por R$ %s no %s.",
                            productName, promo.getUnitPrice(), friendlyMarketName(rule, promo)),
                    baseExtras(rule, promo)));
            rule.setLastFiredAt(now);
            firedRuleIds.add(rule.getId());
            fired.add(rule);
        }
        return fired;
    }

    /**
     * "Someone bought a product I buy, at one of my markets, cheaper than I last paid."
     * Markets in scope: the user's watched markets always; nearby markets too when the
     * user has set a radius on their CHEAPER_MARKET rule (opt-in second toggle).
     * Fires when the observed price is at least 10% below the household's last paid price.
     */
    private List<NotificationRule> fireCheaperMarket(UUID productId, List<PriceObservation> observations,
                                                     Map<String, MarketLocation> locations,
                                                     UUID contributingHouseholdId, LocalDateTime now,
                                                     Set<UUID> firedRuleIds) {
        var owners = ruleRepository.findActiveDefaultRuleOwnersWhoBought(NotificationType.CHEAPER_MARKET, productId);
        if (owners.isEmpty()) return List.of();

        var fired = new ArrayList<NotificationRule>();
        for (var rule : owners) {
            if (firedRuleIds.contains(rule.getId())) continue;
            if (sameHousehold(rule, contributingHouseholdId) || inCooldown(rule, now)) continue;
            var householdId = rule.getUser().getHousehold().getId();
            var lastPaid = lastPaidPrice(productId, householdId);
            if (lastPaid == null) continue;
            var requiredDrop = requiredDropFraction(lastPaid);
            var threshold = lastPaid.multiply(BigDecimal.valueOf(1.0 - requiredDrop));

            var hit = observations.stream()
                    .filter(observation -> isEligibleMarket(rule, observation, locations))
                    .filter(observation -> observation.getUnitPrice().compareTo(threshold) <= 0)
                    .min(Comparator.comparing(PriceObservation::getUnitPrice))
                    .orElse(null);
            if (hit == null) continue;

            var productName = hit.getProduct().getNormalizedName();
            var savingsPct = savingsPercent(lastPaid, hit.getUnitPrice());
            var friendlyMarket = friendlyMarketName(rule, hit);
            var extras = baseExtras(rule, hit);
            extras.put("lastPaidPrice", lastPaid);
            extras.put("savingsPct", savingsPct);
            notificationService.notify(new NotificationPayload(
                    rule.getUser(), NotificationType.CHEAPER_MARKET,
                    "Mais barato em " + friendlyMarket + ": " + productName,
                    String.format("%s saiu por R$ %s no %s — %s%% abaixo dos R$ %s que você pagou da última vez.",
                            productName, hit.getUnitPrice(), friendlyMarket, savingsPct,
                            lastPaid.setScale(2, RoundingMode.HALF_UP)),
                    extras));
            rule.setLastFiredAt(now);
            firedRuleIds.add(rule.getId());
            fired.add(rule);
        }
        return fired;
    }

    /** Watched markets are always in scope; nearby markets only if the rule opts in via radiusKm. */
    private boolean isEligibleMarket(NotificationRule rule, PriceObservation observation,
                                     Map<String, MarketLocation> locations) {
        if (watchedMarketRepository.existsByUserIdAndMarketCnpj(rule.getUser().getId(), observation.getMarketCnpj())) {
            return true;
        }
        return rule.getRadiusKm() != null
                && withinRadius(rule.getRadiusKm(), rule.getUser(), observation, locations);
    }

    /**
     * Required drop fraction for a cheaper-market hit, scaled by price: a deep cut on a
     * cheap item, a shallow one on a pricey item. Interpolated on log-price between the
     * anchors and clamped to [{@link #MIN_DROP}, {@link #MAX_DROP}].
     */
    private double requiredDropFraction(BigDecimal lastPaid) {
        var price = lastPaid.doubleValue();
        var low = LOW_PRICE_ANCHOR.doubleValue();
        var high = HIGH_PRICE_ANCHOR.doubleValue();
        if (price <= low) return MAX_DROP;
        if (price >= high) return MIN_DROP;
        var position = Math.log(price / low) / Math.log(high / low); // 0 at low, 1 at high
        return MAX_DROP - (MAX_DROP - MIN_DROP) * position;
    }

    private BigDecimal savingsPercent(BigDecimal lastPaid, BigDecimal observed) {
        return lastPaid.subtract(observed)
                .divide(lastPaid, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP);
    }

    private BigDecimal lastPaidPrice(UUID productId, UUID householdId) {
        var history = receiptItemRepository.findHouseholdHistoryForProduct(productId, householdId);
        BigDecimal lastPaid = null;
        for (var item : history) { // ordered oldest -> newest; keep the latest non-null unit price
            if (item.getUnitPrice() != null) lastPaid = item.getUnitPrice();
        }
        return lastPaid;
    }

    // ---- shared helpers ----

    private boolean sameHousehold(NotificationRule rule, UUID contributingHouseholdId) {
        return contributingHouseholdId != null
                && rule.getUser().getHousehold() != null
                && contributingHouseholdId.equals(rule.getUser().getHousehold().getId());
    }

    private boolean inCooldown(NotificationRule rule, LocalDateTime now) {
        return rule.getLastFiredAt() != null && rule.getLastFiredAt().isAfter(now.minus(COOLDOWN));
    }

    private boolean withinRadius(Double radiusKm, User user,
                                 PriceObservation observation, Map<String, MarketLocation> locations) {
        if (radiusKm == null) return true;
        var location = locations.get(observation.getMarketCnpj());
        if (user.getHomeLatitude() == null || user.getHomeLongitude() == null
                || location == null || !location.hasCoordinates()) {
            return false; // radius requested but we can't place it — honor the constraint
        }
        var km = DistanceCalculator.kmBetween(
                user.getHomeLatitude(), user.getHomeLongitude(),
                location.getLatitude(), location.getLongitude());
        return km <= radiusKm;
    }

    private String marketName(PriceObservation observation) {
        return observation.getMarketName() != null ? observation.getMarketName() : "um mercado próximo";
    }

    /** The rule owner's household custom name for the market, falling back to the observation's display name. */
    private String friendlyMarketName(NotificationRule rule, PriceObservation observation) {
        var householdId = rule.getUser().getHousehold() != null ? rule.getUser().getHousehold().getId() : null;
        return marketNameService.resolve(householdId, observation.getMarketCnpj(), marketName(observation));
    }

    private Map<String, Object> baseExtras(NotificationRule rule, PriceObservation observation) {
        var householdId = rule.getUser().getHousehold() != null ? rule.getUser().getHousehold().getId() : null;
        var friendly = marketNameService.resolve(householdId, observation.getMarketCnpj(), observation.getMarketName());
        var extras = new HashMap<String, Object>();
        extras.put("ruleId", rule.getId().toString());
        extras.put("productId", observation.getProduct().getId().toString());
        extras.put("observedPrice", observation.getUnitPrice());
        extras.put("marketCnpj", observation.getMarketCnpj());
        extras.put("marketName", observation.getMarketName() != null ? observation.getMarketName() : "");
        extras.put("marketFriendlyName", friendly != null ? friendly : "");
        if (rule.getThresholdPrice() != null) extras.put("thresholdPrice", rule.getThresholdPrice());
        return extras;
    }
}
