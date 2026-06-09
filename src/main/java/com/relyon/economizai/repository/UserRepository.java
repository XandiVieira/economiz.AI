package com.relyon.economizai.repository;

import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.AuthProvider;
import com.relyon.economizai.model.enums.DigestFrequency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    Optional<User> findByAuthProviderAndProviderSubject(AuthProvider authProvider, String providerSubject);

    boolean existsByEmail(String email);

    List<User> findAllByHouseholdId(UUID householdId);

    long countByHouseholdId(UUID householdId);

    /**
     * Active users who haven't turned the deals digest OFF, with household
     * eagerly joined (the scheduler reads it per candidate). This is the digest
     * batch's outer slice; effective-send-hour and 1/day-cap are then applied
     * per user in the scheduler.
     */
    @Query("""
        SELECT user FROM User user
        JOIN FETCH user.household
        WHERE user.active = true
          AND user.digestFrequency <> :off
    """)
    List<User> findDigestCandidates(DigestFrequency off);
}
