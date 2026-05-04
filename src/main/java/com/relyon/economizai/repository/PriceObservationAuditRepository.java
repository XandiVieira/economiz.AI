package com.relyon.economizai.repository;

import com.relyon.economizai.model.PriceObservationAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.UUID;

public interface PriceObservationAuditRepository extends JpaRepository<PriceObservationAudit, UUID> {

    /** K-anonymity helper: how many distinct households contributed observations
     * for a given (product, market) since the cutoff? */
    @Query("""
        SELECT COUNT(DISTINCT a.householdId)
        FROM PriceObservationAudit a
        WHERE a.observation.product.id = :productId
          AND a.observation.marketCnpj = :marketCnpj
          AND a.observation.outlier = false
          AND a.observation.observedAt >= :since
    """)
    long countDistinctHouseholdsForProductMarket(@Param("productId") UUID productId,
                                                 @Param("marketCnpj") String marketCnpj,
                                                 @Param("since") LocalDateTime since);

    @Query("""
        SELECT COUNT(DISTINCT a.householdId)
        FROM PriceObservationAudit a
        WHERE a.observation.product.id = :productId
          AND a.observation.outlier = false
          AND a.observation.observedAt >= :since
    """)
    long countDistinctHouseholdsForProduct(@Param("productId") UUID productId,
                                           @Param("since") LocalDateTime since);

    /**
     * True when another household has already contributed observations for
     * a receipt sharing this fiscal chave. Used to keep the same NF from
     * counting twice in the collaborative panel when two households both
     * import it (e.g. couple split a bill).
     */
    @Query("""
        SELECT COUNT(a) > 0
        FROM PriceObservationAudit a, Receipt r
        WHERE a.receiptId = r.id
          AND r.chaveAcesso = :chaveAcesso
          AND a.householdId <> :currentHouseholdId
    """)
    boolean existsContributionForChaveFromOtherHousehold(@Param("chaveAcesso") String chaveAcesso,
                                                         @Param("currentHouseholdId") UUID currentHouseholdId);
}
