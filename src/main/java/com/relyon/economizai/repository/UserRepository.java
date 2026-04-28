package com.relyon.economizai.repository;

import com.relyon.economizai.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    List<User> findAllByHouseholdId(UUID householdId);

    long countByHouseholdId(UUID householdId);
}
