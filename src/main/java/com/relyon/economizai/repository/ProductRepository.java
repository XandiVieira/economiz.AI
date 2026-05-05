package com.relyon.economizai.repository;

import com.relyon.economizai.model.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    Optional<Product> findByEan(String ean);

    @Query("""
        SELECT p FROM Product p
        WHERE (:query IS NULL OR LOWER(p.normalizedName) LIKE LOWER(CONCAT('%', :query, '%')) OR p.ean = :query)
        ORDER BY p.normalizedName ASC
    """)
    Page<Product> search(@Param("query") String query, Pageable pageable);

    /**
     * Products matching all four metadata dimensions. Used by
     * {@code CanonicalizationService} to dedup against an existing product
     * when an unknown EAN comes in (small markets sometimes use internal
     * pseudo-EANs for the same physical product). Caller should require
     * all four arguments to be non-null — otherwise the candidate pool is
     * too loose and false-positive-prone.
     */
    @Query("""
        SELECT p FROM Product p
        WHERE p.genericName = :genericName
          AND p.brand = :brand
          AND p.packSize = :packSize
          AND p.packUnit = :packUnit
    """)
    List<Product> findByMetadata(@Param("genericName") String genericName,
                                 @Param("brand") String brand,
                                 @Param("packSize") BigDecimal packSize,
                                 @Param("packUnit") String packUnit);
}
