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

import java.time.LocalDateTime;

@Entity
@Table(name = "consumption_snoozes",
        uniqueConstraints = @UniqueConstraint(columnNames = {"household_id", "product_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class ConsumptionSnooze extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "snoozed_until", nullable = false)
    private LocalDateTime snoozedUntil;
}
