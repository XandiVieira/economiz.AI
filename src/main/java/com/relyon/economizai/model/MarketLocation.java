package com.relyon.economizai.model;

import com.relyon.economizai.model.enums.MerchantSegment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Cached geolocation for a market identified by CNPJ. Populated lazily
 * (Nominatim / OSM) so receipt-confirm never blocks on the geocoder.
 *
 * Each Brazilian market unit has its own 14-digit CNPJ; the first 8
 * digits ("cnpj_root") identify the chain (Zaffari, Carrefour, etc.).
 */
@Entity
@Table(name = "market_locations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class MarketLocation extends BaseEntity {

    @Column(nullable = false, unique = true, length = 14)
    private String cnpj;

    @Column(name = "cnpj_root", nullable = false, length = 8)
    private String cnpjRoot;

    @Column(length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "geocoded_at")
    private LocalDateTime geocodedAt;

    @Column(name = "geocode_failed_at")
    private LocalDateTime geocodeFailedAt;

    @Column(name = "geocode_attempts", nullable = false)
    @lombok.Builder.Default
    private int geocodeAttempts = 0;

    @Column(length = 120)
    private String city;

    @Column(length = 2)
    private String state;

    /** Official IBGE municipality code (7 digits), from the BrasilAPI CNPJ lookup. */
    @Column(name = "ibge_city_code", length = 7)
    private String ibgeCityCode;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @lombok.Builder.Default
    private MerchantSegment segment = MerchantSegment.UNKNOWN;

    @Column(name = "segment_classified_at")
    private LocalDateTime segmentClassifiedAt;

    @Column(name = "segment_attempts", nullable = false)
    @lombok.Builder.Default
    private int segmentAttempts = 0;

    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }
}
