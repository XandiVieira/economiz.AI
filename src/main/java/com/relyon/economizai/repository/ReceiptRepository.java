package com.relyon.economizai.repository;

import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.enums.ReceiptStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReceiptRepository extends JpaRepository<Receipt, UUID>, JpaSpecificationExecutor<Receipt> {

    Optional<Receipt> findByChaveAcesso(String chaveAcesso);

    boolean existsByChaveAcesso(String chaveAcesso);

    boolean existsByHouseholdIdAndChaveAcesso(UUID householdId, String chaveAcesso);

    Optional<Receipt> findByHouseholdIdAndChaveAcesso(UUID householdId, String chaveAcesso);

    long countByHouseholdIdAndStatus(UUID householdId, ReceiptStatus status);

    /**
     * How many receipts the user submitted at/after the given instant, ANY
     * status. Counting all statuses (not just CONFIRMED) closes the gaming
     * loop where a FREE user rejects/deletes-and-resubmits to dodge the cap.
     */
    long countByUserIdAndCreatedAtGreaterThanEqual(UUID userId, LocalDateTime since);

    long countByHouseholdIdAndStatusAndConfirmedAtAfter(UUID householdId, ReceiptStatus status, LocalDateTime since);

    /** Total R$ of the household's confirmed receipts confirmed at/after the given instant. */
    @Query("""
        SELECT COALESCE(SUM(r.totalAmount), 0) FROM Receipt r
        WHERE r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND r.confirmedAt >= :since
    """)
    BigDecimal sumConfirmedTotalSince(@Param("householdId") UUID householdId, @Param("since") LocalDateTime since);

    /** Distinct CNPJs the household has ever submitted a confirmed receipt from. */
    @Query("""
        SELECT DISTINCT r.cnpjEmitente FROM Receipt r
        WHERE r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND r.cnpjEmitente IS NOT NULL
    """)
    List<String> findDistinctCnpjsByHousehold(@Param("householdId") UUID householdId);

    /**
     * Eagerly loads items + each item's product so DTO mapping doesn't N+1
     * when the response includes per-item product fields (e.g. category).
     */
    @Query("""
        SELECT DISTINCT r FROM Receipt r
        LEFT JOIN FETCH r.items i
        LEFT JOIN FETCH i.product
        WHERE r.id = :id
    """)
    Optional<Receipt> findByIdWithItemsAndProducts(@Param("id") UUID id);
}
