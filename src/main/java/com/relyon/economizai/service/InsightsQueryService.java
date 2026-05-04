package com.relyon.economizai.service;

import com.relyon.economizai.dto.response.InsightsQueryResponse;
import com.relyon.economizai.dto.response.InsightsQueryResponse.Bucket;
import com.relyon.economizai.dto.response.InsightsQueryResponse.Filters;
import com.relyon.economizai.dto.response.InsightsQueryResponse.Summary;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.InsightsGroupBy;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.model.enums.ReceiptStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Flexible spend-analytics query service.
 *
 * <p>One endpoint, many slices: combine date / market / category / product /
 * EAN / receipt-total filters with any single {@link InsightsGroupBy}
 * dimension. Filters that accept multiple values (categories, marketCnpjs,
 * productIds, eans, marketCnpjRoots) take a list — every list-typed filter
 * is OR'd internally and AND'd with the others.
 *
 * <p>Single responsibility: assemble + run the dynamic JPQL and shape it into
 * the response. The fixed-shape {@link InsightsService} stays untouched for
 * backwards compatibility with the existing dashboards.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsightsQueryService {

    private static final LocalDateTime EPOCH_FLOOR = LocalDateTime.of(1900, 1, 1, 0, 0);
    private static final LocalDateTime EPOCH_CEIL = LocalDateTime.of(2999, 12, 31, 23, 59, 59);
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public InsightsQueryResponse query(User user, QueryFilters input) {
        var filters = QueryFilters.normalize(input, user.getHousehold().getId());
        var groupBy = filters.groupBy();
        var summary = computeSummary(filters);
        var buckets = groupBy == InsightsGroupBy.NONE
                ? List.<Bucket>of()
                : computeBuckets(filters);

        log.info("insights.query household={} groupBy={} buckets={} total={} receipts={}",
                filters.householdId(), groupBy, buckets.size(),
                summary.total(), summary.receiptCount());

        return new InsightsQueryResponse(
                new Filters(
                        input.from(), input.to(),
                        filters.marketCnpjs(), filters.marketCnpjRoots(),
                        filters.categories(), filters.productIds(), filters.eans(),
                        filters.minReceiptTotal(), filters.maxReceiptTotal()),
                summary,
                groupBy,
                buckets);
    }

    private Summary computeSummary(QueryFilters f) {
        var clauses = buildClauses(f);
        var jpql = """
                SELECT COALESCE(SUM(ri.totalPrice), 0),
                       COUNT(DISTINCT r.id),
                       COUNT(ri)
                FROM ReceiptItem ri
                JOIN ri.receipt r
                LEFT JOIN ri.product p
                WHERE %s
                """.formatted(clauses.where());
        var query = entityManager.createQuery(jpql, Object[].class);
        clauses.bind(query);
        var row = query.getSingleResult();
        var total = (BigDecimal) row[0];
        var receiptCount = ((Number) row[1]).longValue();
        var itemCount = ((Number) row[2]).longValue();
        return new Summary(total, receiptCount, itemCount, averageTicket(total, receiptCount));
    }

    private List<Bucket> computeBuckets(QueryFilters f) {
        var dimension = Dimension.forGroupBy(f.groupBy());
        var clauses = buildClauses(f);
        var jpql = """
                SELECT %s,
                       COALESCE(SUM(ri.totalPrice), 0),
                       COUNT(DISTINCT r.id),
                       COUNT(ri)
                FROM ReceiptItem ri
                JOIN ri.receipt r
                LEFT JOIN ri.product p
                WHERE %s
                GROUP BY %s
                ORDER BY %s
                """.formatted(dimension.selectKeys(), clauses.where(),
                dimension.groupByKeys(), dimension.orderBy());
        var query = entityManager.createQuery(jpql, Object[].class);
        clauses.bind(query);
        query.setMaxResults(f.limit());

        return query.getResultList().stream()
                .map(dimension::toBucket)
                .toList();
    }

    /**
     * Assembles the WHERE clause + parameter bindings dynamically. Each
     * optional filter is added ONLY when its value is non-null — Hibernate
     * can't infer the right type when the same parameter would have to
     * satisfy both a scalar {@code IS NULL} check AND a collection
     * {@code IN} clause, which is what a single static query template
     * would require.
     */
    private static FilterClauses buildClauses(QueryFilters f) {
        var clauses = new ArrayList<String>();
        var bindings = new LinkedHashMap<String, Object>();

        clauses.add("r.household.id = :householdId");
        bindings.put("householdId", f.householdId());

        clauses.add("r.status = :status");
        bindings.put("status", ReceiptStatus.CONFIRMED);

        clauses.add("ri.excluded = false");

        clauses.add("r.issuedAt >= :from");
        bindings.put("from", f.from());
        clauses.add("r.issuedAt <= :to");
        bindings.put("to", f.to());

        if (f.marketCnpjs() != null) {
            clauses.add("r.cnpjEmitente IN (:marketCnpjs)");
            bindings.put("marketCnpjs", f.marketCnpjs());
        }
        if (f.marketCnpjRoots() != null) {
            clauses.add("SUBSTRING(r.cnpjEmitente, 1, 8) IN (:marketCnpjRoots)");
            bindings.put("marketCnpjRoots", f.marketCnpjRoots());
        }
        if (f.categories() != null) {
            clauses.add("p.category IN (:categories)");
            bindings.put("categories", f.categories());
        }
        if (f.productIds() != null) {
            clauses.add("p.id IN (:productIds)");
            bindings.put("productIds", f.productIds());
        }
        if (f.eans() != null) {
            clauses.add("ri.ean IN (:eans)");
            bindings.put("eans", f.eans());
        }
        if (f.minReceiptTotal() != null) {
            clauses.add("r.totalAmount >= :minReceiptTotal");
            bindings.put("minReceiptTotal", f.minReceiptTotal());
        }
        if (f.maxReceiptTotal() != null) {
            clauses.add("r.totalAmount <= :maxReceiptTotal");
            bindings.put("maxReceiptTotal", f.maxReceiptTotal());
        }
        return new FilterClauses(String.join(" AND ", clauses), bindings);
    }

    private record FilterClauses(String where, Map<String, Object> bindings) {
        void bind(Query query) {
            bindings.forEach(query::setParameter);
        }
    }

    private static BigDecimal averageTicket(BigDecimal total, long receiptCount) {
        return receiptCount == 0
                ? BigDecimal.ZERO
                : total.divide(BigDecimal.valueOf(receiptCount), 2, RoundingMode.HALF_UP);
    }

    /**
     * Raw input from the controller. Lists/ranges may be null or empty;
     * {@link #normalize} turns empties into nulls so the {@code IS NULL OR IN}
     * clauses behave correctly.
     */
    public record QueryFilters(
            UUID householdId,
            LocalDateTime from,
            LocalDateTime to,
            List<String> marketCnpjs,
            List<String> marketCnpjRoots,
            List<ProductCategory> categories,
            List<UUID> productIds,
            List<String> eans,
            BigDecimal minReceiptTotal,
            BigDecimal maxReceiptTotal,
            InsightsGroupBy groupBy,
            int limit
    ) {
        /** Builder for the controller — householdId is filled in by the service. */
        public static QueryFilters fromRequest(LocalDateTime from, LocalDateTime to,
                                               List<String> marketCnpjs,
                                               List<String> marketCnpjRoots,
                                               List<ProductCategory> categories,
                                               List<UUID> productIds,
                                               List<String> eans,
                                               BigDecimal minReceiptTotal,
                                               BigDecimal maxReceiptTotal,
                                               InsightsGroupBy groupBy,
                                               Integer limit) {
            return new QueryFilters(null, from, to,
                    marketCnpjs, marketCnpjRoots, categories, productIds, eans,
                    minReceiptTotal, maxReceiptTotal,
                    groupBy != null ? groupBy : InsightsGroupBy.NONE,
                    clampLimit(limit));
        }

        static QueryFilters normalize(QueryFilters f, UUID householdId) {
            return new QueryFilters(
                    householdId,
                    f.from() != null ? f.from() : EPOCH_FLOOR,
                    f.to() != null ? f.to() : EPOCH_CEIL,
                    nullIfEmpty(trimAll(f.marketCnpjs())),
                    nullIfEmpty(trimAll(f.marketCnpjRoots())),
                    nullIfEmpty(f.categories()),
                    nullIfEmpty(f.productIds()),
                    nullIfEmpty(trimAll(f.eans())),
                    f.minReceiptTotal(),
                    f.maxReceiptTotal(),
                    f.groupBy(),
                    f.limit()
            );
        }

        private static <T> List<T> nullIfEmpty(List<T> list) {
            return list == null || list.isEmpty() ? null : list;
        }

        private static List<String> trimAll(List<String> list) {
            if (list == null) return null;
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }

        private static int clampLimit(Integer limit) {
            if (limit == null || limit <= 0) return DEFAULT_LIMIT;
            return Math.min(limit, MAX_LIMIT);
        }
    }

    /**
     * Maps an {@link InsightsGroupBy} to JPQL fragments + row→bucket
     * conversion. Encapsulating each dimension here keeps the service body
     * focused on assembly, not per-dimension shape.
     */
    private enum Dimension {
        DAY(
                "EXTRACT(YEAR FROM r.issuedAt), EXTRACT(MONTH FROM r.issuedAt), EXTRACT(DAY FROM r.issuedAt)",
                "EXTRACT(YEAR FROM r.issuedAt), EXTRACT(MONTH FROM r.issuedAt), EXTRACT(DAY FROM r.issuedAt)",
                "EXTRACT(YEAR FROM r.issuedAt) ASC, EXTRACT(MONTH FROM r.issuedAt) ASC, EXTRACT(DAY FROM r.issuedAt) ASC") {
            @Override Bucket toBucket(Object[] row) {
                var key = "%04d-%02d-%02d".formatted(intAt(row, 0), intAt(row, 1), intAt(row, 2));
                return bucketOf(key, key, row, 3);
            }
        },
        WEEK(
                "EXTRACT(YEAR FROM r.issuedAt), EXTRACT(WEEK FROM r.issuedAt)",
                "EXTRACT(YEAR FROM r.issuedAt), EXTRACT(WEEK FROM r.issuedAt)",
                "EXTRACT(YEAR FROM r.issuedAt) ASC, EXTRACT(WEEK FROM r.issuedAt) ASC") {
            @Override Bucket toBucket(Object[] row) {
                var key = "%04d-W%02d".formatted(intAt(row, 0), intAt(row, 1));
                return bucketOf(key, key, row, 2);
            }
        },
        MONTH(
                "EXTRACT(YEAR FROM r.issuedAt), EXTRACT(MONTH FROM r.issuedAt)",
                "EXTRACT(YEAR FROM r.issuedAt), EXTRACT(MONTH FROM r.issuedAt)",
                "EXTRACT(YEAR FROM r.issuedAt) ASC, EXTRACT(MONTH FROM r.issuedAt) ASC") {
            @Override Bucket toBucket(Object[] row) {
                var key = "%04d-%02d".formatted(intAt(row, 0), intAt(row, 1));
                return bucketOf(key, key, row, 2);
            }
        },
        YEAR(
                "EXTRACT(YEAR FROM r.issuedAt)",
                "EXTRACT(YEAR FROM r.issuedAt)",
                "EXTRACT(YEAR FROM r.issuedAt) ASC") {
            @Override Bucket toBucket(Object[] row) {
                var key = String.valueOf(intAt(row, 0));
                return bucketOf(key, key, row, 1);
            }
        },
        MARKET(
                "r.cnpjEmitente, MAX(r.marketName)",
                "r.cnpjEmitente",
                "SUM(ri.totalPrice) DESC") {
            @Override Bucket toBucket(Object[] row) {
                var cnpj = (String) row[0];
                var marketName = (String) row[1];
                return bucketOf(cnpj, marketName != null ? marketName : cnpj, row, 2);
            }
        },
        CHAIN(
                "SUBSTRING(r.cnpjEmitente, 1, 8), MAX(r.marketName)",
                "SUBSTRING(r.cnpjEmitente, 1, 8)",
                "SUM(ri.totalPrice) DESC") {
            @Override Bucket toBucket(Object[] row) {
                var cnpjRoot = (String) row[0];
                var sampleName = (String) row[1];
                return bucketOf(cnpjRoot, sampleName != null ? sampleName : cnpjRoot, row, 2);
            }
        },
        CATEGORY(
                "p.category",
                "p.category",
                "SUM(ri.totalPrice) DESC") {
            @Override Bucket toBucket(Object[] row) {
                var category = row[0] == null ? ProductCategory.OTHER : (ProductCategory) row[0];
                return bucketOf(category.name(), category.name(), row, 1);
            }
        },
        PRODUCT(
                "p.id, MAX(p.normalizedName)",
                "p.id",
                "SUM(ri.totalPrice) DESC") {
            @Override Bucket toBucket(Object[] row) {
                var productId = row[0] == null ? null : ((UUID) row[0]).toString();
                var name = (String) row[1];
                var label = name != null ? name : productId;
                return bucketOf(productId != null ? productId : "unknown", label, row, 2);
            }
        };

        private final String selectKeys;
        private final String groupByKeys;
        private final String orderBy;

        Dimension(String selectKeys, String groupByKeys, String orderBy) {
            this.selectKeys = selectKeys;
            this.groupByKeys = groupByKeys;
            this.orderBy = orderBy;
        }

        String selectKeys() { return selectKeys; }
        String groupByKeys() { return groupByKeys; }
        String orderBy() { return orderBy; }

        abstract Bucket toBucket(Object[] row);

        static Dimension forGroupBy(InsightsGroupBy groupBy) {
            return switch (groupBy) {
                case DAY -> DAY;
                case WEEK -> WEEK;
                case MONTH -> MONTH;
                case YEAR -> YEAR;
                case MARKET -> MARKET;
                case CHAIN -> CHAIN;
                case CATEGORY -> CATEGORY;
                case PRODUCT -> PRODUCT;
                case NONE -> throw new IllegalStateException("NONE handled by caller");
            };
        }

        protected static Bucket bucketOf(String key, String label, Object[] row, int aggregateOffset) {
            var total = (BigDecimal) row[aggregateOffset];
            var receiptCount = ((Number) row[aggregateOffset + 1]).longValue();
            var itemCount = ((Number) row[aggregateOffset + 2]).longValue();
            return new Bucket(key, label, total, receiptCount, itemCount,
                    averageTicket(total, receiptCount));
        }

        protected static int intAt(Object[] row, int idx) {
            return ((Number) row[idx]).intValue();
        }
    }
}
