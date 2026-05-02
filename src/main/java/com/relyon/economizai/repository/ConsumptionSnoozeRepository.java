package com.relyon.economizai.repository;

import com.relyon.economizai.model.ConsumptionSnooze;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsumptionSnoozeRepository extends JpaRepository<ConsumptionSnooze, UUID> {

    Optional<ConsumptionSnooze> findByHouseholdIdAndProductId(UUID householdId, UUID productId);

    List<ConsumptionSnooze> findAllByHouseholdIdAndSnoozedUntilAfter(UUID householdId, LocalDateTime now);

    void deleteByHouseholdIdAndProductId(UUID householdId, UUID productId);
}
