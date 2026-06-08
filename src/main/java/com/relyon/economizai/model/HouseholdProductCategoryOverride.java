package com.relyon.economizai.model;

import com.relyon.economizai.model.enums.ProductCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * A household's manual category correction for a product — "evidence, not truth".
 * It overrides the category that household sees (the global {@link Product} stays
 * as-is, so other households are unaffected), and each row is a vote a later
 * consensus pass can use to refine the global default.
 *
 * <p>Mirrors {@link HouseholdProductAlias} (per-household friendly name) but for
 * category. One row per (household, product).
 */
@Entity
@Table(name = "household_product_category_overrides",
        uniqueConstraints = @UniqueConstraint(columnNames = {"household_id", "product_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class HouseholdProductCategoryOverride extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Target global category — null when the override targets a custom category. */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ProductCategory category;

    /** Target custom category — null when the override targets a global enum. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "custom_category_id")
    private HouseholdCustomCategory customCategory;

    /** The category label this household sees: custom name if set, else the enum name. */
    public String effectiveLabel() {
        if (customCategory != null) return customCategory.getName();
        return category != null ? category.name() : null;
    }

    /**
     * A discriminated bucket key that never collides across the custom/enum spaces:
     * {@code "custom:<id>"} for a custom category, {@code "enum:<name>"} for an enum.
     * Two different categories that happen to share a display label (e.g. a custom
     * category literally named "OTHER") therefore stay in separate buckets.
     */
    public String effectiveKey() {
        if (customCategory != null) return "custom:" + customCategory.getId();
        return category != null ? "enum:" + category.name() : null;
    }
}

