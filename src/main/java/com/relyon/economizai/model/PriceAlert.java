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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A user's "avise-me quando" rule: notify me when {@link #product} is seen
 * at or below {@link #thresholdPrice} anywhere in the collaborative price
 * index (optionally only within {@link #radiusKm} of the user's home).
 *
 * <p>The retention loop of the community panel — one household's confirmed
 * receipt fires another household's alert. Evaluated on the write path in
 * {@code PriceIndexService.recordContributions}.
 *
 * <p>One rule per (user, product); re-creating for the same product updates
 * the existing rule rather than duplicating it.
 */
@Entity
@Table(name = "price_alerts",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "product_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PriceAlert extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Fire when an observed unit price is at or below this (R$). */
    @Column(name = "threshold_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal thresholdPrice;

    /** Optional geo scope from the user's home. Null = anywhere in the network. */
    @Column(name = "radius_km")
    private Double radiusKm;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean active = true;

    /** Last time this rule fired — drives the anti-spam cooldown. */
    @Column(name = "last_fired_at")
    private LocalDateTime lastFiredAt;
}
