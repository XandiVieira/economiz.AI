package com.relyon.economizai.repository;

import com.relyon.economizai.model.ProductAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductAliasRepository extends JpaRepository<ProductAlias, UUID> {

    Optional<ProductAlias> findByNormalizedDescription(String normalizedDescription);

    boolean existsByNormalizedDescription(String normalizedDescription);

    /**
     * Aliases of products matching the given extracted metadata profile.
     * Used by {@code CanonicalizationService} as the candidate pool for
     * fuzzy alias matching — restricted to same genericName + pack size to
     * keep false-positive risk low.
     */
    @Query("""
        SELECT a FROM ProductAlias a
        JOIN a.product p
        WHERE p.genericName = :genericName
          AND p.packSize = :packSize
          AND p.packUnit = :packUnit
    """)
    List<ProductAlias> findCandidatesByProductMetadata(@Param("genericName") String genericName,
                                                       @Param("packSize") BigDecimal packSize,
                                                       @Param("packUnit") String packUnit);
}
