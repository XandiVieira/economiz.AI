package com.relyon.economizai.repository;

import com.relyon.economizai.model.ManualBrandPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ManualBrandPreferenceRepository extends JpaRepository<ManualBrandPreference, UUID> {

    List<ManualBrandPreference> findAllByHouseholdId(UUID householdId);

    Optional<ManualBrandPreference> findByHouseholdIdAndGenericName(UUID householdId, String genericName);
}
