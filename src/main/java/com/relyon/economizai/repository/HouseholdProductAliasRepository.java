package com.relyon.economizai.repository;

import com.relyon.economizai.model.HouseholdProductAlias;
import com.relyon.economizai.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HouseholdProductAliasRepository extends JpaRepository<HouseholdProductAlias, UUID> {

    Optional<HouseholdProductAlias> findByHouseholdIdAndProductId(UUID householdId, UUID productId);

    List<HouseholdProductAlias> findAllByHouseholdIdAndProductIdIn(UUID householdId, List<UUID> productIds);

    long countByProduct(Product product);

    @Modifying
    @Query("""
        DELETE FROM HouseholdProductAlias h
        WHERE h.product = :absorbed
          AND h.household.id IN (SELECT h2.household.id FROM HouseholdProductAlias h2 WHERE h2.product = :survivor)
    """)
    int deleteAbsorbedConflictsWithSurvivor(@Param("absorbed") Product absorbed, @Param("survivor") Product survivor);

    @Modifying
    @Query("UPDATE HouseholdProductAlias h SET h.product = :survivor WHERE h.product = :absorbed")
    int repointProduct(@Param("absorbed") Product absorbed, @Param("survivor") Product survivor);
}
