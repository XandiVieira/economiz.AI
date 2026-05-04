package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.enums.InsightsGroupBy;
import com.relyon.economizai.model.enums.ProductCategory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response from the flexible {@code GET /api/v1/insights/query} endpoint.
 *
 * <ul>
 *   <li>{@code filters} — echoes back the inputs that scoped the query, so the
 *       FE can render "Spend on GROCERIES + BEVERAGES at Zaffari, Apr 2026"
 *       headers without re-tracking state.</li>
 *   <li>{@code summary} — total / receipt-count / item-count / ticket médio
 *       computed across the whole filtered slice. Always present.</li>
 *   <li>{@code groupBy} — what dimension the buckets are grouped by.</li>
 *   <li>{@code buckets} — one row per dimension value. Empty when groupBy is
 *       {@code NONE}. Sorted DESC by total for non-temporal groupings (market,
 *       category, product, chain) and ASC by time for temporal ones.</li>
 * </ul>
 */
public record InsightsQueryResponse(
        Filters filters,
        Summary summary,
        InsightsGroupBy groupBy,
        List<Bucket> buckets
) {
    /**
     * Multi-value filters (categories, marketCnpjs, etc.) are exposed as
     * {@link List}s. Range filters appear as paired min/max fields. Any field
     * that wasn't supplied is null/empty in the echo.
     */
    public record Filters(
            LocalDateTime from,
            LocalDateTime to,
            List<String> marketCnpjs,
            List<String> marketCnpjRoots,
            List<ProductCategory> categories,
            List<UUID> productIds,
            List<String> eans,
            BigDecimal minReceiptTotal,
            BigDecimal maxReceiptTotal
    ) {}

    public record Summary(
            BigDecimal total,
            long receiptCount,
            long itemCount,
            BigDecimal averageTicket
    ) {}

    /**
     * One grouped row. {@code key} is the canonical machine value (e.g.
     * {@code "GROCERIES"}, {@code "93015006005182"}, {@code "2026-04"}, a
     * product UUID). {@code label} is a human-friendly version when the key
     * isn't readable on its own (e.g. market name for a CNPJ key, product name
     * for a product-id key).
     */
    public record Bucket(
            String key,
            String label,
            BigDecimal total,
            long receiptCount,
            long itemCount,
            BigDecimal averageTicket
    ) {}
}
