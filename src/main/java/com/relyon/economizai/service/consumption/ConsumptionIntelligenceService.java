package com.relyon.economizai.service.consumption;

import com.relyon.economizai.config.CollaborativeProperties;
import com.relyon.economizai.dto.request.LogManualPurchaseRequest;
import com.relyon.economizai.dto.request.SnoozeProductRequest;
import com.relyon.economizai.dto.response.ConsumptionPredictionResponse;
import com.relyon.economizai.dto.response.SuggestedShoppingListResponse;
import com.relyon.economizai.exception.ProductNotFoundException;
import com.relyon.economizai.model.ConsumptionSnooze;
import com.relyon.economizai.model.ManualPurchase;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.ConsumptionSnoozeRepository;
import com.relyon.economizai.repository.ManualPurchaseRepository;
import com.relyon.economizai.repository.ProductRepository;
import com.relyon.economizai.repository.ReceiptItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 3 — Consumption Intelligence.
 *
 * <p>Per (household, product), uses confirmed-receipt items + manually-logged
 * purchases (PRO-51 "Já comprei") to estimate (a) average days between
 * purchases, and (b) the date the user is likely to buy that product again.
 * Suggested-list = predictions in {@code RAN_OUT}/{@code RUNNING_LOW}, with
 * snoozed products excluded.
 *
 * <p>Quantity-aware adjustment (PRO-50 AC4): if the last purchase was
 * markedly larger than usual (e.g. user bought 3× the typical pack), the
 * next-purchase ETA scales proportionally — bigger basket → lasts longer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsumptionIntelligenceService {

    private final ReceiptItemRepository receiptItemRepository;
    private final ManualPurchaseRepository manualPurchaseRepository;
    private final ConsumptionSnoozeRepository snoozeRepository;
    private final ProductRepository productRepository;
    private final CollaborativeProperties properties;

    @Transactional(readOnly = true)
    public List<ConsumptionPredictionResponse> predict(User user) {
        var consumption = properties.getConsumption();
        if (!consumption.isEnabled()) return List.of();

        var householdId = user.getHousehold().getId();
        var lookbackCutoff = LocalDateTime.now().minusDays(consumption.getHistoryLookbackDays());
        var byProduct = collectPurchases(householdId, lookbackCutoff);
        if (byProduct.isEmpty()) {
            log.debug("consumption.predict.empty reason=no_history household={}", householdId);
            return List.of();
        }

        var snoozedProductIds = activeSnoozes(householdId);
        var predictions = new ArrayList<ConsumptionPredictionResponse>();
        for (var entry : byProduct.entrySet()) {
            if (snoozedProductIds.contains(entry.getKey())) continue;
            var prediction = predictForProduct(entry.getValue(), consumption);
            if (prediction != null) predictions.add(prediction);
        }

        predictions.sort(Comparator.comparing(ConsumptionPredictionResponse::daysUntilNextPurchase));
        log.info("consumption.predict.done household={} products={} snoozed={}",
                householdId, predictions.size(), snoozedProductIds.size());
        return predictions;
    }

    @Transactional(readOnly = true)
    public SuggestedShoppingListResponse suggestedList(User user, boolean includeUpcoming, int upcomingLimit) {
        var predictions = predict(user);
        var lowOrOut = predictions.stream()
                .filter(p -> p.status() == ConsumptionPredictionResponse.Status.RAN_OUT
                        || p.status() == ConsumptionPredictionResponse.Status.RUNNING_LOW)
                .toList();
        if (!includeUpcoming) {
            return new SuggestedShoppingListResponse(lowOrOut, LocalDateTime.now());
        }
        var upcoming = predictions.stream()
                .filter(p -> p.status() == ConsumptionPredictionResponse.Status.OK)
                .limit(upcomingLimit)
                .toList();
        var combined = new ArrayList<ConsumptionPredictionResponse>(lowOrOut.size() + upcoming.size());
        combined.addAll(lowOrOut);
        combined.addAll(upcoming);
        return new SuggestedShoppingListResponse(combined, LocalDateTime.now());
    }

    @Transactional
    public void snooze(User user, UUID productId, SnoozeProductRequest request) {
        var product = productRepository.findById(productId).orElseThrow(ProductNotFoundException::new);
        var householdId = user.getHousehold().getId();
        var until = LocalDateTime.now().plusDays(request.days());
        var snooze = snoozeRepository.findByHouseholdIdAndProductId(householdId, productId)
                .orElseGet(() -> ConsumptionSnooze.builder()
                        .household(user.getHousehold())
                        .product(product)
                        .build());
        snooze.setSnoozedUntil(until);
        snoozeRepository.save(snooze);
        log.info("consumption.snooze.set household={} product={} until={}", householdId, productId, until);
    }

    @Transactional
    public void unsnooze(User user, UUID productId) {
        snoozeRepository.deleteByHouseholdIdAndProductId(user.getHousehold().getId(), productId);
        log.info("consumption.snooze.cleared household={} product={}",
                user.getHousehold().getId(), productId);
    }

    @Transactional
    public void logManualPurchase(User user, LogManualPurchaseRequest request) {
        var product = productRepository.findById(request.productId()).orElseThrow(ProductNotFoundException::new);
        var purchasedAt = request.purchasedAt() != null ? request.purchasedAt() : LocalDateTime.now();
        manualPurchaseRepository.save(ManualPurchase.builder()
                .household(user.getHousehold())
                .user(user)
                .product(product)
                .quantity(request.quantity())
                .purchasedAt(purchasedAt)
                .build());
        // Logging a manual purchase implicitly clears any active snooze on that product
        // — the user just took action on it.
        snoozeRepository.deleteByHouseholdIdAndProductId(user.getHousehold().getId(), request.productId());
        log.info("consumption.manual_purchase.logged household={} product={} qty={} at={}",
                user.getHousehold().getId(), product.getId(), request.quantity(), purchasedAt);
    }

    private Map<UUID, List<PurchaseEvent>> collectPurchases(UUID householdId, LocalDateTime lookbackCutoff) {
        var byProduct = new HashMap<UUID, List<PurchaseEvent>>();
        for (var item : receiptItemRepository.findConfirmedHistoryForHousehold(householdId)) {
            var receipt = item.getReceipt();
            if (receipt.getIssuedAt() == null || receipt.getIssuedAt().isBefore(lookbackCutoff)) continue;
            byProduct.computeIfAbsent(item.getProduct().getId(), k -> new ArrayList<>())
                    .add(new PurchaseEvent(receipt.getIssuedAt().toLocalDate(),
                            item.getQuantity() == null ? BigDecimal.ONE : item.getQuantity(),
                            item.getProduct()));
        }
        for (var manual : manualPurchaseRepository.findAllByHouseholdId(householdId)) {
            if (manual.getPurchasedAt().isBefore(lookbackCutoff)) continue;
            byProduct.computeIfAbsent(manual.getProduct().getId(), k -> new ArrayList<>())
                    .add(new PurchaseEvent(manual.getPurchasedAt().toLocalDate(),
                            manual.getQuantity(), manual.getProduct()));
        }
        return byProduct;
    }

    private Set<UUID> activeSnoozes(UUID householdId) {
        return snoozeRepository.findAllByHouseholdIdAndSnoozedUntilAfter(householdId, LocalDateTime.now()).stream()
                .map(s -> s.getProduct().getId())
                .collect(java.util.stream.Collectors.toSet());
    }

    private ConsumptionPredictionResponse predictForProduct(List<PurchaseEvent> events,
                                                            CollaborativeProperties.Consumption cfg) {
        if (events.size() < cfg.getMinPurchasesForPrediction()) return null;

        events.sort(Comparator.comparing(PurchaseEvent::date));
        var dates = events.stream().map(PurchaseEvent::date).distinct().toList();
        if (dates.size() < cfg.getMinPurchasesForPrediction()) return null;

        var avgIntervalDays = averageIntervalDays(dates);
        if (avgIntervalDays <= 0) return null;

        var avgQty = averageQty(events);
        var lastEvent = events.get(events.size() - 1);
        var qtyMultiplier = quantityMultiplier(lastEvent.quantity(), avgQty);
        var adjustedInterval = avgIntervalDays * qtyMultiplier;

        var nextPurchase = lastEvent.date().plusDays(Math.round(adjustedInterval));
        var daysUntil = Duration.between(LocalDate.now().atStartOfDay(),
                nextPurchase.atStartOfDay()).toDays();
        var status = classifyStatus(daysUntil, cfg);

        return new ConsumptionPredictionResponse(
                lastEvent.product().getId(),
                lastEvent.product().getNormalizedName(),
                lastEvent.product().getCategory(),
                lastEvent.date(),
                nextPurchase,
                daysUntil,
                BigDecimal.valueOf(adjustedInterval).setScale(1, RoundingMode.HALF_UP),
                avgQty,
                events.size(),
                confidenceFor(events.size()),
                status
        );
    }

    private double quantityMultiplier(BigDecimal lastQty, BigDecimal avgQty) {
        if (lastQty == null || avgQty == null || avgQty.signum() <= 0) return 1.0;
        var ratio = lastQty.doubleValue() / avgQty.doubleValue();
        // Clamp to [1.0, 5.0] so a single weird outlier (e.g. wholesale run)
        // can't push the next ETA arbitrarily far. <1.0 is treated as 1.0 —
        // buying less than usual doesn't shrink the cycle, the user is
        // probably topping up.
        if (ratio < 1.0) return 1.0;
        if (ratio > 5.0) return 5.0;
        return ratio;
    }

    private double averageIntervalDays(List<LocalDate> dates) {
        var intervals = new ArrayList<Long>();
        for (int i = 1; i < dates.size(); i++) {
            intervals.add(Duration.between(dates.get(i - 1).atStartOfDay(),
                    dates.get(i).atStartOfDay()).toDays());
        }
        return intervals.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    private BigDecimal averageQty(List<PurchaseEvent> events) {
        var sum = events.stream()
                .map(PurchaseEvent::quantity)
                .filter(q -> q != null && q.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(events.size()), 3, RoundingMode.HALF_UP);
    }

    private ConsumptionPredictionResponse.Status classifyStatus(long daysUntil,
                                                                CollaborativeProperties.Consumption c) {
        if (daysUntil < -c.getRanOutGraceDays()) return ConsumptionPredictionResponse.Status.RAN_OUT;
        if (daysUntil <= c.getRunningLowThresholdDays()) return ConsumptionPredictionResponse.Status.RUNNING_LOW;
        return ConsumptionPredictionResponse.Status.OK;
    }

    private ConsumptionPredictionResponse.Confidence confidenceFor(int sampleSize) {
        if (sampleSize >= 8) return ConsumptionPredictionResponse.Confidence.HIGH;
        if (sampleSize >= 5) return ConsumptionPredictionResponse.Confidence.MEDIUM;
        return ConsumptionPredictionResponse.Confidence.LOW;
    }

    private record PurchaseEvent(LocalDate date, BigDecimal quantity, Product product) {}
}
