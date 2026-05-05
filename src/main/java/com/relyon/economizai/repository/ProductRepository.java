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

    /**
     * Products without a brand — used by the admin curation endpoint
     * (Item C.2). Sorted by normalizedName so the same product surfaces
     * in the same place across pages.
     */
    @Query("""
        SELECT p FROM Product p
        WHERE p.brand IS NULL OR p.brand = ''
        ORDER BY p.normalizedName ASC
    """)
    Page<Product> findMissingBrand(Pageable pageable);

    /**
     * All products that share a `(genericName, brand, packSize, packUnit)`
     * tuple with at least one other product — i.e. probable duplicates that
     * the admin merge tool should surface. Sorted so duplicates of the same
     * profile come back contiguously and the service layer can group them.
     * Only considers products with all four metadata dimensions populated;
     * items missing any dimension can't be reliably deduped this way (and
     * are already filtered out from the runtime metadata-dedup gate).
     */
    @Query("""
        SELECT p FROM Product p
        WHERE p.genericName IS NOT NULL
          AND p.brand IS NOT NULL
          AND p.packSize IS NOT NULL
          AND p.packUnit IS NOT NULL
          AND EXISTS (
              SELECT 1 FROM Product p2
              WHERE p2.id <> p.id
                AND p2.genericName = p.genericName
                AND p2.brand = p.brand
                AND p2.packSize = p.packSize
                AND p2.packUnit = p.packUnit
          )
        ORDER BY p.genericName ASC, p.brand ASC, p.packSize ASC, p.packUnit ASC, p.createdAt ASC
    """)
    List<Product> findDuplicateCandidates();
}
