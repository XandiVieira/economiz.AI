package com.relyon.economizai.repository;

import com.relyon.economizai.model.DealSurfaceState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DealSurfaceStateRepository extends JpaRepository<DealSurfaceState, UUID> {

    Optional<DealSurfaceState> findByUserIdAndProductIdAndMarketCnpj(UUID userId, UUID productId, String marketCnpj);
}
