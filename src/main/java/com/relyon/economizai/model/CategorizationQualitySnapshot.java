package com.relyon.economizai.model;

import com.relyon.economizai.model.enums.CategorizationQualityTrigger;
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

/**
 * One point in the categorization-quality time series. Immutable (insert-only);
 * {@code createdAt} from {@link BaseEntity} is the recorded time. Written on
 * every benchmark run and every re-categorization backfill so the trend is
 * queryable.
 */
@Entity
@Table(name = "categorization_quality_snapshots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CategorizationQualitySnapshot extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CategorizationQualityTrigger trigger;

    /** % of the golden set categorized correctly by the cascade. */
    @Column(name = "accuracy_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal accuracyPct;

    @Column(name = "benchmark_total", nullable = false)
    private int benchmarkTotal;

    @Column(name = "benchmark_correct", nullable = false)
    private int benchmarkCorrect;

    @Column(name = "catalog_products", nullable = false)
    private int catalogProducts;

    @Column(name = "catalog_categorized", nullable = false)
    private int catalogCategorized;

    /** % of real products that have any category (coverage of the live catalog). */
    @Column(name = "catalog_coverage_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal catalogCoveragePct;

    @Column(name = "ml_ready", nullable = false)
    private boolean mlReady;
}
