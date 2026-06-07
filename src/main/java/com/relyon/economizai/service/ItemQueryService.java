package com.relyon.economizai.service;

import com.relyon.economizai.dto.response.PurchasedItemResponse;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.CategoryView;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.service.geo.MarketNameService;
import com.relyon.economizai.service.subscription.SubscriptionGateService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Item-level analog of {@link InsightsQueryService}: same filter vocabulary
 * (date / market / category / product / EAN / receipt-total), but instead of
 * aggregating it returns the raw purchased line items, paginated and newest
 * first. Powers {@code GET /items} — e.g. "show me every item I've bought in
 * MEAT_DAIRY".
 *
 * <p>Scope matches the analytics view: CONFIRMED receipts only, household-scoped,
 * excluded items dropped — i.e. real purchases. The dynamic JPQL mirrors the
 * insights slicer; the filter clause shape is deliberately kept identical so
 * the two endpoints accept the same params. (If a third consumer appears,
 * extract the clause builder into a shared component.)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemQueryService {

    private static final LocalDateTime EPOCH_FLOOR = LocalDateTime.of(1900, 1, 1, 0, 0);
    private static final LocalDateTime EPOCH_CEIL = LocalDateTime.of(2999, 12, 31, 23, 59, 59);

    private final HouseholdProductCategoryOverrideService categoryOverrideService;
    private final MarketNameService marketNameService;
    private final SubscriptionGateService subscriptionGate;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public Page<PurchasedItemResponse> query(User user, ItemFilters input, Pageable pageable) {
        // FREE tier: clamp the requested lower bound to the allowed history window.
        var clampedFrom = subscriptionGate.clampFrom(user, input.from());
        var clamped = new ItemFilters(input.householdId(), clampedFrom, input.to(),
                input.marketCnpjs(), input.marketCnpjRoots(), input.categories(),
                input.productIds(), input.eans(), input.minReceiptTotal(),
                input.maxReceiptTotal(), input.categoryView());
        var filters = ItemFilters.normalize(clamped, user.getHousehold().getId());
        var clauses = buildClauses(filters);

        var countQuery = entityManager.createQuery(
                "SELECT COUNT(ri) FROM ReceiptItem ri JOIN ri.receipt r LEFT JOIN ri.product p"
                        + clauses.join() + " WHERE " + clauses.where(), Long.class);
        clauses.bind(countQuery);
        var total = countQuery.getSingleResult();

        List<PurchasedItemResponse> rows = List.of();
        if (total > 0) {
            var rowQuery = entityManager.createQuery(
                    "SELECT ri FROM ReceiptItem ri JOIN FETCH ri.receipt r LEFT JOIN FETCH ri.product p"
                            + clauses.join() + " WHERE " + clauses.where()
                            + " ORDER BY r.issuedAt DESC, ri.id ASC", ReceiptItem.class);
            clauses.bind(rowQuery);
            rowQuery.setFirstResult((int) pageable.getOffset());
            rowQuery.setMaxResults(pageable.getPageSize());
            var items = rowQuery.getResultList();
            var productIds = items.stream()
                    .filter(item -> item.getProduct() != null)
                    .map(item -> item.getProduct().getId())
                    .distinct()
                    .toList();
            var overrides = categoryOverrideService.overridesByProduct(filters.householdId(), productIds);
            var marketCnpjs = items.stream()
                    .map(item -> item.getReceipt().getCnpjEmitente())
                    .filter(cnpj -> cnpj != null)
                    .distinct()
                    .toList();
            var marketOverrides = marketNameService.resolveNames(filters.householdId(), marketCnpjs);
            rows = items.stream()
                    .map(item -> PurchasedItemResponse.from(item,
                                    item.getProduct() == null ? null : overrides.get(item.getProduct().getId()))
                            .withMarketFriendlyName(marketNameService.applyOverride(marketOverrides,
                                    item.getReceipt().getCnpjEmitente(), item.getReceipt().getMarketName())))
                    .toList();
        }
        log.info("items.query household={} total={} returned={}", filters.householdId(), total, rows.size());
        return new PageImpl<>(rows, pageable, total);
    }

    private static FilterClauses buildClauses(ItemFilters f) {
        var clauses = new ArrayList<String>();
        var bindings = new LinkedHashMap<String, Object>();
        var join = "";

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
            if (f.categoryView() == CategoryView.HOUSEHOLD) {
                // Household lens: filter by EFFECTIVE category. A product matches when
                // it has no override and its global category is in the list, OR its
                // override targets an enum in the list. Products migrated to a custom
                // category (override.category IS NULL) or to a different enum are excluded.
                join = " LEFT JOIN HouseholdProductCategoryOverride o"
                        + " ON o.product = p AND o.household.id = :householdId";
                clauses.add("((o.id IS NULL AND p.category IN (:categories)) OR o.category IN (:categories))");
            } else {
                clauses.add("p.category IN (:categories)");
            }
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
        return new FilterClauses(join, String.join(" AND ", clauses), bindings);
    }

    private record FilterClauses(String join, String where, Map<String, Object> bindings) {
        void bind(Query query) {
            bindings.forEach(query::setParameter);
        }
    }

    /**
     * Filter inputs for {@code GET /items}. Mirrors
     * {@link InsightsQueryService.QueryFilters} minus groupBy/limit (pagination
     * + a fixed newest-first sort replace those). Empty lists normalize to null
     * so an absent filter is a no-op.
     */
    public record ItemFilters(
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
            CategoryView categoryView
    ) {
        /** Backwards-compatible builder — defaults the lens to HOUSEHOLD. */
        public static ItemFilters fromRequest(LocalDateTime from, LocalDateTime to,
                                              List<String> marketCnpjs,
                                              List<String> marketCnpjRoots,
                                              List<ProductCategory> categories,
                                              List<UUID> productIds,
                                              List<String> eans,
                                              BigDecimal minReceiptTotal,
                                              BigDecimal maxReceiptTotal) {
            return fromRequest(from, to, marketCnpjs, marketCnpjRoots, categories,
                    productIds, eans, minReceiptTotal, maxReceiptTotal, CategoryView.HOUSEHOLD);
        }

        public static ItemFilters fromRequest(LocalDateTime from, LocalDateTime to,
                                              List<String> marketCnpjs,
                                              List<String> marketCnpjRoots,
                                              List<ProductCategory> categories,
                                              List<UUID> productIds,
                                              List<String> eans,
                                              BigDecimal minReceiptTotal,
                                              BigDecimal maxReceiptTotal,
                                              CategoryView categoryView) {
            return new ItemFilters(null, from, to, marketCnpjs, marketCnpjRoots, categories,
                    productIds, eans, minReceiptTotal, maxReceiptTotal,
                    categoryView != null ? categoryView : CategoryView.HOUSEHOLD);
        }

        static ItemFilters normalize(ItemFilters f, UUID householdId) {
            return new ItemFilters(
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
                    f.categoryView() != null ? f.categoryView() : CategoryView.HOUSEHOLD);
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
    }
}
