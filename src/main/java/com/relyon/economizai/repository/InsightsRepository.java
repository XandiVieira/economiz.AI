package com.relyon.economizai.repository;

import com.relyon.economizai.model.Receipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface InsightsRepository extends JpaRepository<Receipt, UUID> {

    @Query("""
        SELECT COALESCE(SUM(r.totalAmount), 0)
        FROM Receipt r
        WHERE r.household.id = :householdId
          AND r.status = com.relyon.economizai.model.enums.ReceiptStatus.CONFIRMED
          AND r.issuedAt >= :from
          AND r.issuedAt <= :to
    """)
    BigDecimal totalSpend(@Param("householdId") UUID householdId,
                          @Param("from") LocalDateTime from,
                          @Param("to") LocalDateTime to);

    @Query("""
        SELECT EXTRACT(YEAR FROM r.issuedAt) AS year,
               EXTRACT(MONTH FROM r.issuedAt) AS month,
               COALESCE(SUM(r.totalAmount), 0) AS total,
               COUNT(r) AS receiptCount
        FROM Receipt r
        WHERE r.household.id = :householdId
          AND r.status = com.relyon.economizai.model.enums.ReceiptStatus.CONFIRMED
          AND r.issuedAt >= :from
          AND r.issuedAt <= :to
        GROUP BY EXTRACT(YEAR FROM r.issuedAt), EXTRACT(MONTH FROM r.issuedAt)
        ORDER BY year ASC, month ASC
    """)
    List<Object[]> spendByMonth(@Param("householdId") UUID householdId,
                                @Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to);

    @Query("""
        SELECT r.cnpjEmitente AS cnpj,
               MAX(r.marketName) AS marketName,
               COALESCE(SUM(r.totalAmount), 0) AS total,
               COUNT(r) AS receiptCount
        FROM Receipt r
        WHERE r.household.id = :householdId
          AND r.status = com.relyon.economizai.model.enums.ReceiptStatus.CONFIRMED
          AND r.issuedAt >= :from
          AND r.issuedAt <= :to
        GROUP BY r.cnpjEmitente
        ORDER BY total DESC
    """)
    List<Object[]> spendByMarket(@Param("householdId") UUID householdId,
                                 @Param("from") LocalDateTime from,
                                 @Param("to") LocalDateTime to);

    @Query("""
        SELECT COALESCE(p.category, com.relyon.economizai.model.enums.ProductCategory.OTHER) AS category,
               COALESCE(SUM(ri.totalPrice), 0) AS total,
               COUNT(ri) AS itemCount
        FROM ReceiptItem ri
        JOIN ri.receipt r
        LEFT JOIN ri.product p
        WHERE r.household.id = :householdId
          AND r.status = com.relyon.economizai.model.enums.ReceiptStatus.CONFIRMED
          AND r.issuedAt >= :from
          AND r.issuedAt <= :to
        GROUP BY COALESCE(p.category, com.relyon.economizai.model.enums.ProductCategory.OTHER)
        ORDER BY total DESC
    """)
    List<Object[]> spendByCategory(@Param("householdId") UUID householdId,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to);

    @Query("""
        SELECT r.issuedAt AS issuedAt,
               r.marketName AS marketName,
               ri.unitPrice AS unitPrice,
               ri.quantity AS quantity
        FROM ReceiptItem ri
        JOIN ri.receipt r
        WHERE r.household.id = :householdId
          AND r.status = com.relyon.economizai.model.enums.ReceiptStatus.CONFIRMED
          AND ri.product.id = :productId
          AND r.issuedAt >= :from
          AND r.issuedAt <= :to
        ORDER BY r.issuedAt ASC
    """)
    List<Object[]> priceHistoryForProduct(@Param("householdId") UUID householdId,
                                          @Param("productId") UUID productId,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);
}
