package com.relyon.economizai.service.consumption;

import com.relyon.economizai.config.CollaborativeProperties;
import com.relyon.economizai.dto.response.ConsumptionPredictionResponse;
import com.relyon.economizai.dto.response.SuggestedShoppingListResponse;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.User;
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
import java.util.List;
import java.util.stream.Collectors;

/**
 * Phase 3 — Consumption Intelligence.
 *
 * <p>Per (household, product), uses the household's purchase history of
 * confirmed receipts to estimate (a) average days between purchases, and
 * (b) the date the user is likely to buy that product again. The
 * "suggested shopping list" is the union of products predicted to run out
 * within {@link CollaborativeProperties.Consumption#getRunningLowThresholdDays()}
 * days plus already-overdue items.
 *
 * <p><b>Volume gate:</b> requires at least
 * {@link CollaborativeProperties.Consumption#getMinPurchasesForPrediction()}
 * prior purchases of a given product. Below that threshold, the product
 * is silently skipped (we don't show low-confidence predictions).
 *
 * <p><b>Confidence label:</b> coarse — LOW (just hit the minimum), MEDIUM
 * (5+ purchases), HIGH (8+). Lets the FE down-weight noisy estimates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsumptionIntelligenceService {

    private final ReceiptItemRepository receiptItemRepository;
    private final CollaborativeProperties properties;

    @Transactional(readOnly = true)
    public List<ConsumptionPredictionResponse> predict(User user) {
        var consumption = properties.getConsumption();
        if (!consumption.isEnabled()) return List.of();

        var householdId = user.getHousehold().getId();
        var lookbackCutoff = LocalDateTime.now().minusDays(consumption.getHistoryLookbackDays());
        var history = receiptItemRepository.findConfirmedHistoryForHousehold(householdId).stream()
                .filter(item -> item.getReceipt().getIssuedAt() != null
                        && !item.getReceipt().getIssuedAt().isBefore(lookbackCutoff))
                .toList();
        if (history.isEmpty()) {
            log.debug("consumption.predict.empty reason=no_history household={}", householdId);
            return List.of();
        }

        var byProduct = history.stream()
                .collect(Collectors.groupingBy(item -> item.getProduct().getId()));

        var predictions = new ArrayList<ConsumptionPredictionResponse>();
        var now = LocalDate.now();
        for (var entry : byProduct.entrySet()) {
            var items = entry.getValue();
            if (items.size() < consumption.getMinPurchasesForPrediction()) continue;

            var dates = items.stream()
                    .map(i -> i.getReceipt().getIssuedAt().toLocalDate())
                    .sorted()
                    .distinct()
                    .toList();
            if (dates.size() < consumption.getMinPurchasesForPrediction()) continue;

            var avgIntervalDays = averageIntervalDays(dates);
            if (avgIntervalDays <= 0) continue;

            var lastPurchase = dates.get(dates.size() - 1);
            var nextPurchase = lastPurchase.plusDays(Math.round(avgIntervalDays));
            var daysUntil = Duration.between(now.atStartOfDay(), nextPurchase.atStartOfDay()).toDays();

            var status = classifyStatus(daysUntil, consumption);
            var product = items.get(items.size() - 1).getProduct();
            predictions.add(buildPrediction(product, items, dates, lastPurchase, nextPurchase,
                    avgIntervalDays, daysUntil, status));
        }

        predictions.sort(Comparator.comparing(ConsumptionPredictionResponse::daysUntilNextPurchase));
        log.info("consumption.predict.done household={} products={}", householdId, predictions.size());
        return predictions;
    }

    @Transactional(readOnly = true)
    public SuggestedShoppingListResponse suggestedList(User user) {
        var predictions = predict(user);
        var suggested = predictions.stream()
                .filter(p -> p.status() == ConsumptionPredictionResponse.Status.RAN_OUT
                        || p.status() == ConsumptionPredictionResponse.Status.RUNNING_LOW)
                .toList();
        return new SuggestedShoppingListResponse(suggested, LocalDateTime.now());
    }

    private double averageIntervalDays(List<LocalDate> dates) {
        var intervals = new ArrayList<Long>();
        for (int i = 1; i < dates.size(); i++) {
            intervals.add(Duration.between(dates.get(i - 1).atStartOfDay(),
                    dates.get(i).atStartOfDay()).toDays());
        }
        return intervals.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    private ConsumptionPredictionResponse.Status classifyStatus(long daysUntil,
                                                                CollaborativeProperties.Consumption c) {
        if (daysUntil < -c.getRanOutGraceDays()) return ConsumptionPredictionResponse.Status.RAN_OUT;
        if (daysUntil <= c.getRunningLowThresholdDays()) return ConsumptionPredictionResponse.Status.RUNNING_LOW;
        return ConsumptionPredictionResponse.Status.OK;
    }

    private ConsumptionPredictionResponse buildPrediction(Product product, List<ReceiptItem> items,
                                                          List<LocalDate> dates, LocalDate lastPurchase,
                                                          LocalDate nextPurchase, double avgIntervalDays,
                                                          long daysUntil,
                                                          ConsumptionPredictionResponse.Status status) {
        var avgQty = items.stream()
                .map(ReceiptItem::getQuantity)
                .filter(q -> q != null && q.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(items.size()), 3, RoundingMode.HALF_UP);
        var confidence = confidenceFor(items.size());
        return new ConsumptionPredictionResponse(
                product.getId(),
                product.getNormalizedName(),
                product.getCategory(),
                lastPurchase,
                nextPurchase,
                daysUntil,
                BigDecimal.valueOf(avgIntervalDays).setScale(1, RoundingMode.HALF_UP),
                avgQty,
                items.size(),
                confidence,
                status
        );
    }

    private ConsumptionPredictionResponse.Confidence confidenceFor(int sampleSize) {
        if (sampleSize >= 8) return ConsumptionPredictionResponse.Confidence.HIGH;
        if (sampleSize >= 5) return ConsumptionPredictionResponse.Confidence.MEDIUM;
        return ConsumptionPredictionResponse.Confidence.LOW;
    }
}
