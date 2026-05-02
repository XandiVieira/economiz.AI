package com.relyon.economizai.repository;

import com.relyon.economizai.model.HouseholdProductAlias;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HouseholdProductAliasRepository extends JpaRepository<HouseholdProductAlias, UUID> {

    Optional<HouseholdProductAlias> findByHouseholdIdAndProductId(UUID householdId, UUID productId);

    List<HouseholdProductAlias> findAllByHouseholdIdAndProductIdIn(UUID householdId, List<UUID> productIds);
}
