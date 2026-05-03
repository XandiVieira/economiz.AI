package com.relyon.economizai.repository;

import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.enums.ReceiptStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReceiptRepository extends JpaRepository<Receipt, UUID>, JpaSpecificationExecutor<Receipt> {

    Optional<Receipt> findByChaveAcesso(String chaveAcesso);

    boolean existsByChaveAcesso(String chaveAcesso);

    boolean existsByHouseholdIdAndChaveAcesso(UUID householdId, String chaveAcesso);

    long countByHouseholdIdAndStatus(UUID householdId, ReceiptStatus status);

    /** Distinct CNPJs the household has ever submitted a confirmed receipt from. */
    @Query("""
        SELECT DISTINCT r.cnpjEmitente FROM Receipt r
        WHERE r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND r.cnpjEmitente IS NOT NULL
    """)
    List<String> findDistinctCnpjsByHousehold(@Param("householdId") UUID householdId);
}
