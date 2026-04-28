package com.relyon.economizai.repository;

import com.relyon.economizai.model.ProductAlias;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProductAliasRepository extends JpaRepository<ProductAlias, UUID> {

    Optional<ProductAlias> findByNormalizedDescription(String normalizedDescription);

    boolean existsByNormalizedDescription(String normalizedDescription);
}
