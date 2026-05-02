package com.relyon.economizai.repository;

import com.relyon.economizai.model.ManualPurchase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ManualPurchaseRepository extends JpaRepository<ManualPurchase, UUID> {

    List<ManualPurchase> findAllByHouseholdId(UUID householdId);
}
