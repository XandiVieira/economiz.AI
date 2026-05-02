package com.relyon.economizai.service;

import com.relyon.economizai.dto.response.PriceHistoryResponse;
import com.relyon.economizai.dto.response.SpendInsightsResponse;
import com.relyon.economizai.exception.ProductNotFoundException;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.repository.InsightsRepository;
import com.relyon.economizai.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InsightsService {

    private static final LocalDateTime EPOCH_FLOOR = LocalDateTime.of(1900, 1, 1, 0, 0);
    private static final LocalDateTime EPOCH_CEIL = LocalDateTime.of(2999, 12, 31, 23, 59, 59);

    private final InsightsRepository insightsRepository;
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public SpendInsightsResponse spend(User user, LocalDateTime from, LocalDateTime to) {
        var householdId = user.getHousehold().getId();
        var fromBound = from != null ? from : EPOCH_FLOOR;
        var toBound = to != null ? to : EPOCH_CEIL;
        var total = insightsRepository.totalSpend(householdId, fromBound, toBound);
        var byMonth = insightsRepository.spendByMonth(householdId, fromBound, toBound).stream()
                .map(row -> new SpendInsightsResponse.MonthBucket(
                        ((Number) row[0]).intValue(),
                        ((Number) row[1]).intValue(),
                        toBigDecimal(row[2]),
                        ((Number) row[3]).longValue()))
                .toList();
        var byWeek = insightsRepository.spendByWeek(householdId, fromBound, toBound).stream()
                .map(row -> new SpendInsightsResponse.WeekBucket(
                        ((Number) row[0]).intValue(),
                        ((Number) row[1]).intValue(),
                        toBigDecimal(row[2]),
                        ((Number) row[3]).longValue()))
                .toList();
        var byMarket = insightsRepository.spendByMarket(householdId, fromBound, toBound).stream()
                .map(row -> new SpendInsightsResponse.MarketBucket(
                        (String) row[0],
                        (String) row[1],
                        toBigDecimal(row[2]),
                        ((Number) row[3]).longValue()))
                .toList();
        var byCategory = insightsRepository.spendByCategory(householdId, fromBound, toBound).stream()
                .map(row -> new SpendInsightsResponse.CategoryBucket(
                        (ProductCategory) row[0],
                        toBigDecimal(row[1]),
                        ((Number) row[2]).longValue()))
                .toList();
        log.debug("Spend insights for household {} (from={}, to={}): total={}", householdId, from, to, total);
        return new SpendInsightsResponse(from, to, total, byMonth, byWeek, byMarket, byCategory);
    }

    @Transactional(readOnly = true)
    public List<SpendInsightsResponse.MarketBucket> topMarkets(User user, LocalDateTime from, LocalDateTime to, int limit) {
        return spend(user, from, to).byMarket().stream().limit(limit).toList();
    }

    @Transactional(readOnly = true)
    public List<SpendInsightsResponse.CategoryBucket> topCategories(User user, LocalDateTime from, LocalDateTime to, int limit) {
        return spend(user, from, to).byCategory().stream().limit(limit).toList();
    }

    @Transactional(readOnly = true)
    public PriceHistoryResponse priceHistory(User user, UUID productId, LocalDateTime from, LocalDateTime to) {
        var product = productRepository.findById(productId).orElseThrow(ProductNotFoundException::new);
        var fromBound = from != null ? from : EPOCH_FLOOR;
        var toBound = to != null ? to : EPOCH_CEIL;
        var points = insightsRepository
                .priceHistoryForProduct(user.getHousehold().getId(), productId, fromBound, toBound).stream()
                .map(row -> new PriceHistoryResponse.PricePoint(
                        (LocalDateTime) row[0],
                        (String) row[1],
                        (String) row[2],
                        toBigDecimal(row[3]),
                        toBigDecimal(row[4])))
                .toList();
        return new PriceHistoryResponse(product.getId(), product.getNormalizedName(), points);
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(value.toString());
    }
}
