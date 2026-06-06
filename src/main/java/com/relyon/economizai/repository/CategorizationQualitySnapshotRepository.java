package com.relyon.economizai.repository;

import com.relyon.economizai.model.CategorizationQualitySnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CategorizationQualitySnapshotRepository extends JpaRepository<CategorizationQualitySnapshot, UUID> {

    List<CategorizationQualitySnapshot> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
