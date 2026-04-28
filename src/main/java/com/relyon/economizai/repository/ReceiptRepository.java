package com.relyon.economizai.repository;

import com.relyon.economizai.model.Receipt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReceiptRepository extends JpaRepository<Receipt, UUID> {

    Page<Receipt> findAllByHouseholdIdOrderByIssuedAtDesc(UUID householdId, Pageable pageable);

    Optional<Receipt> findByChaveAcesso(String chaveAcesso);

    boolean existsByChaveAcesso(String chaveAcesso);
}
