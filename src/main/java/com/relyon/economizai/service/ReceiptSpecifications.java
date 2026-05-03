package com.relyon.economizai.service;

import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.model.enums.ReceiptStatus;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Builds JPA Specifications for receipt searches. Centralized so both the
 * household-scoped user view ({@link ReceiptService#list}) and the
 * admin-scoped cross-household view share the same predicate logic for
 * date / market / category / content-search filters.
 */
public final class ReceiptSpecifications {

    private ReceiptSpecifications() {}

    /**
     * @param householdId when non-null, restricts to receipts in that household.
     *                    Pass null for cross-household admin queries.
     * @param hideFailedParse true for the user-facing list (failed-parse rows
     *                        are noise); false for admin (you want to see them).
     */
    public static Specification<Receipt> forSearch(UUID householdId,
                                                   LocalDateTime from,
                                                   LocalDateTime to,
                                                   String cnpj,
                                                   ProductCategory category,
                                                   String search,
                                                   boolean hideFailedParse) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            if (householdId != null) {
                predicates.add(cb.equal(root.get("household").get("id"), householdId));
            }
            // FAILED_PARSE rows are kept for ops review (PRO-43) but hidden from
            // the user history — the user didn't actually buy anything from a
            // failed scan, so it would just be noise in their "compras" list.
            if (hideFailedParse) {
                predicates.add(cb.notEqual(root.get("status"), ReceiptStatus.FAILED_PARSE));
            }
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("issuedAt"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("issuedAt"), to));
            if (cnpj != null) predicates.add(cb.equal(root.get("cnpjEmitente"), cnpj));
            // category + search both require joining items, so do it once and
            // reuse — apply distinct, since a receipt can have many matching items.
            if (category != null || search != null) {
                if (query != null) query.distinct(true);
                var items = root.join("items", JoinType.INNER);
                if (category != null) {
                    var product = items.join("product", JoinType.INNER);
                    predicates.add(cb.equal(product.get("category"), category));
                }
                if (search != null) {
                    var like = "%" + search.toLowerCase() + "%";
                    var productLeft = items.join("product", JoinType.LEFT);
                    predicates.add(cb.or(
                            cb.like(cb.lower(items.get("rawDescription")), like),
                            cb.like(cb.lower(items.get("friendlyDescription")), like),
                            cb.like(cb.lower(productLeft.get("normalizedName")), like),
                            cb.like(cb.lower(root.get("marketName")), like)
                    ));
                }
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
