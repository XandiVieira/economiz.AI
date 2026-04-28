package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.enums.ProductCategory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record SpendInsightsResponse(
        LocalDateTime from,
        LocalDateTime to,
        BigDecimal total,
        List<MonthBucket> byMonth,
        List<MarketBucket> byMarket,
        List<CategoryBucket> byCategory
) {
    public record MonthBucket(int year, int month, BigDecimal total, long receiptCount) {}
    public record MarketBucket(String cnpj, String marketName, BigDecimal total, long receiptCount) {}
    public record CategoryBucket(ProductCategory category, BigDecimal total, long itemCount) {}
}
