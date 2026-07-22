package com.relyon.economizai.repository;

import com.relyon.economizai.model.LlmDisagreement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LlmDisagreementRepository extends JpaRepository<LlmDisagreement, UUID> {

    List<LlmDisagreement> findTop100ByResolvedAtIsNullOrderByCreatedAtDesc();

    long countByResolvedAtIsNull();

    boolean existsByProductIdAndFieldAndResolvedAtIsNull(UUID productId, String field);
}
