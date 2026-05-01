package com.relyon.economizai.repository;

import com.relyon.economizai.model.LearnedDictionaryEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LearnedDictionaryRepository extends JpaRepository<LearnedDictionaryEntry, UUID> {

    Optional<LearnedDictionaryEntry> findByNormalizedToken(String normalizedToken);
}
