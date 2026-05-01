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
}
