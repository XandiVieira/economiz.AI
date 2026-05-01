package com.relyon.economizai.repository;

import com.relyon.economizai.model.UserWatchedMarket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserWatchedMarketRepository extends JpaRepository<UserWatchedMarket, UUID> {

    List<UserWatchedMarket> findAllByUserId(UUID userId);

    Optional<UserWatchedMarket> findByUserIdAndMarketCnpj(UUID userId, String marketCnpj);

    void deleteByUserIdAndMarketCnpj(UUID userId, String marketCnpj);
}
