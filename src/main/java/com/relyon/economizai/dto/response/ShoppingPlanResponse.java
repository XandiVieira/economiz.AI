package com.relyon.economizai.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Output of POST /shopping-list/optimize. Greedy heuristic groups the
 * requested items per market to minimize total cost without modeling
 * travel time. Items the system has no price for (locally or in the
 * collaborative panel) land in {@link #unpriced}.
 */
public record ShoppingPlanResponse(
        List<MarketPlan> marketPlans,
        BigDecimal estimatedTotal,
        List<UnpricedItem> unpriced
) {
    public record MarketPlan(
            String marketCnpj,
            String marketName,
            BigDecimal subtotal,
            int itemCount,
            List<PlanItem> items
    ) {}

    public record PlanItem(
            UUID productId,
            String productName,
            BigDecimal quantity,
            BigDecimal estimatedUnitPrice,
            BigDecimal estimatedSubtotal,
            PriceSource priceSource
    ) {
        public enum PriceSource { LOCAL_HISTORY, COMMUNITY_INDEX }
    }

    public record UnpricedItem(
            UUID productId,
            String productName,
            BigDecimal quantity,
            String reason
    ) {}
}
