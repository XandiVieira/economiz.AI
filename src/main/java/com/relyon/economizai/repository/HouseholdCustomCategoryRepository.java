package com.relyon.economizai.repository;

import com.relyon.economizai.model.HouseholdCustomCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HouseholdCustomCategoryRepository extends JpaRepository<HouseholdCustomCategory, UUID> {

    List<HouseholdCustomCategory> findByHouseholdIdOrderByName(UUID householdId);

    Optional<HouseholdCustomCategory> findByHouseholdIdAndNameIgnoreCase(UUID householdId, String name);

    Optional<HouseholdCustomCategory> findByIdAndHouseholdId(UUID id, UUID householdId);
}
