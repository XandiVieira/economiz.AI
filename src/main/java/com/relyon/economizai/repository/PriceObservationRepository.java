package com.relyon.economizai.repository;

import com.relyon.economizai.model.PriceObservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface PriceObservationRepository extends JpaRepository<PriceObservation, UUID> {

    @Query("""
        SELECT po FROM PriceObservation po
        WHERE po.product.id = :productId
          AND po.marketCnpj = :marketCnpj
          AND po.outlier = false
          AND po.observedAt >= :since
        ORDER BY po.observedAt DESC
    """)
    List<PriceObservation> findRecentByProductAndMarket(@Param("productId") UUID productId,
                                                       @Param("marketCnpj") String marketCnpj,
                                                       @Param("since") LocalDateTime since);

    @Query("""
        SELECT po FROM PriceObservation po
        WHERE po.product.id = :productId
          AND po.outlier = false
          AND po.observedAt >= :since
        ORDER BY po.observedAt DESC
    """)
    List<PriceObservation> findRecentByProduct(@Param("productId") UUID productId,
                                               @Param("since") LocalDateTime since);

    @Query("""
        SELECT po FROM PriceObservation po
        WHERE po.observedAt >= :since
          AND po.outlier = false
    """)
    List<PriceObservation> findRecent(@Param("since") LocalDateTime since);
}
