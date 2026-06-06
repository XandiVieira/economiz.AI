package com.relyon.economizai.repository;

import com.relyon.economizai.model.PriceAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PriceAlertRepository extends JpaRepository<PriceAlert, UUID> {

    @Query("select a from PriceAlert a join fetch a.product where a.user.id = :userId order by a.createdAt desc")
    List<PriceAlert> findAllByUserIdFetchProduct(@Param("userId") UUID userId);

    Optional<PriceAlert> findByIdAndUserId(UUID id, UUID userId);

    Optional<PriceAlert> findByUserIdAndProductId(UUID userId, UUID productId);

    /** Active rules matching any of the given products — used on the write path. */
    @Query("select a from PriceAlert a join fetch a.user where a.product.id in :productIds and a.active = true")
    List<PriceAlert> findActiveByProductIdInFetchUser(@Param("productIds") Collection<UUID> productIds);
}
