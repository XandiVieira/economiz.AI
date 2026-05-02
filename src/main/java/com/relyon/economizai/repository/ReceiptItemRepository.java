package com.relyon.economizai.repository;

import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.ReceiptItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ReceiptItemRepository extends JpaRepository<ReceiptItem, UUID> {

    @Query("""
        SELECT ri FROM ReceiptItem ri
        JOIN FETCH ri.receipt r
        WHERE r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND ri.product IS NULL
          AND ri.excluded = false
        ORDER BY r.issuedAt DESC NULLS LAST, ri.lineNumber ASC
    """)
    List<ReceiptItem> findUnmatchedForHousehold(@Param("householdId") UUID householdId);

    @Modifying
    @Query("""
        UPDATE ReceiptItem ri SET ri.product = :product
        WHERE ri.product IS NULL AND LOWER(ri.ean) = LOWER(:ean) AND ri.excluded = false
    """)
    int linkByEan(@Param("product") Product product, @Param("ean") String ean);

    List<ReceiptItem> findAllByProductIdOrderByReceiptIssuedAtAsc(UUID productId);

    /** Same intent as the method above but fetches receipt + household up front,
     *  used by promo detection where we filter by household + receipt status per row.
     *  Excludes items the household marked as not-mine. */
    @Query("""
        SELECT ri FROM ReceiptItem ri
        JOIN FETCH ri.receipt r
        JOIN FETCH r.household
        WHERE ri.product.id = :productId
          AND r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND ri.excluded = false
        ORDER BY r.issuedAt ASC
    """)
    List<ReceiptItem> findHouseholdHistoryForProduct(@Param("productId") UUID productId,
                                                     @Param("householdId") UUID householdId);

    /** All confirmed, non-excluded purchases of any product by this household, oldest first.
     *  Joins receipt + product so callers can build per-product histories without N+1. */
    @Query("""
        SELECT ri FROM ReceiptItem ri
        JOIN FETCH ri.receipt r
        JOIN FETCH ri.product p
        WHERE r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND ri.product IS NOT NULL
          AND ri.excluded = false
        ORDER BY r.issuedAt ASC, ri.lineNumber ASC
    """)
    List<ReceiptItem> findConfirmedHistoryForHousehold(@Param("householdId") UUID householdId);
}
