package com.relyon.economizai.repository;

import com.relyon.economizai.model.HouseholdProductCategoryOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HouseholdProductCategoryOverrideRepository
        extends JpaRepository<HouseholdProductCategoryOverride, UUID> {

    List<HouseholdProductCategoryOverride> findAllByOriginHouseholdId(UUID householdId);

    Optional<HouseholdProductCategoryOverride> findByHouseholdIdAndProductId(UUID householdId, UUID productId);

    List<HouseholdProductCategoryOverride> findAllByHouseholdId(UUID householdId);

    List<HouseholdProductCategoryOverride> findByHouseholdIdAndProductIdIn(UUID householdId, List<UUID> productIds);

    List<HouseholdProductCategoryOverride> findByHouseholdIdAndCustomCategoryId(UUID householdId, UUID customCategoryId);
}
