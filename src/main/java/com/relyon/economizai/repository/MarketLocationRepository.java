package com.relyon.economizai.repository;

import com.relyon.economizai.model.MarketLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MarketLocationRepository extends JpaRepository<MarketLocation, UUID> {

    Optional<MarketLocation> findByCnpj(String cnpj);

    List<MarketLocation> findAllByCnpjIn(List<String> cnpjs);

    /** Markets we know about but haven't successfully geocoded yet (for the
     *  scheduled geocoding job). */
    List<MarketLocation> findAllByLatitudeIsNullAndGeocodeAttemptsLessThan(int maxAttempts);
}
