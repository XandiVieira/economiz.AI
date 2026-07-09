package com.relyon.economizai.repository;

import com.relyon.economizai.model.PaidApiCall;
import com.relyon.economizai.model.enums.PaidApiService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface PaidApiCallRepository extends JpaRepository<PaidApiCall, UUID> {

    /** How many calls a user has made to a paid service since {@code since} — the daily-cap counter. */
    long countByUserIdAndServiceAndCreatedAtGreaterThanEqual(UUID userId, PaidApiService service, OffsetDateTime since);
}
