package com.relyon.economizai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Household-level custom display name for a market (by CNPJ). When a household
 * renames "ZAFFARI COMERCIO..." to "Zaffari de casa", that name is shown to
 * that household everywhere a market appears — never globally and never to
 * other households.
 *
 * <p>Mirrors {@link HouseholdProductAlias} (per-household product naming).
 * Keyed by CNPJ rather than a MarketLocation FK so a rename works even before
 * a market has a geocoded {@code market_locations} row.
 */
@Entity
@Table(name = "household_market_aliases",
        uniqueConstraints = @UniqueConstraint(columnNames = {"household_id", "market_cnpj"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class HouseholdMarketAlias extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    @Column(name = "market_cnpj", nullable = false, length = 14)
    private String marketCnpj;

    @Column(name = "custom_name", nullable = false, length = 255)
    private String customName;
}
