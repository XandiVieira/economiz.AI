package com.relyon.economizai.repository;

import com.relyon.economizai.model.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
