package com.relyon.economizai.repository;

import com.relyon.economizai.model.DataShareConsent;
import com.relyon.economizai.model.enums.ConsentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DataShareConsentRepository extends JpaRepository<DataShareConsent, UUID> {

    // The grantor's inbox: requests awaiting their decision.
    List<DataShareConsent> findByGrantorIdAndStatus(UUID grantorId, ConsentStatus status);

    List<DataShareConsent> findByRequesterId(UUID requesterId);

    Optional<DataShareConsent> findByIdAndGrantorId(UUID id, UUID grantorId);
}
