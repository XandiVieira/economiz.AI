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
 * A user-defined category, scoped to a household (e.g. "FRUITS"). Overlays the
 * global {@link com.relyon.economizai.model.enums.ProductCategory} enum: the
 * household can migrate its products into it. One name per household.
 */
@Entity
@Table(name = "household_custom_categories",
        uniqueConstraints = @UniqueConstraint(columnNames = {"household_id", "name"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class HouseholdCustomCategory extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    @Column(nullable = false, length = 60)
    private String name;
}
