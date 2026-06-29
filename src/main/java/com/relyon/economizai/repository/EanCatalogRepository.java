package com.relyon.economizai.repository;

import com.relyon.economizai.model.EanCatalogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EanCatalogRepository extends JpaRepository<EanCatalogEntry, UUID> {

    Optional<EanCatalogEntry> findByEan(String ean);

    List<EanCatalogEntry> findByEanIn(Collection<String> eans);
}
