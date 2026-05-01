package com.relyon.economizai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The atomic fact of the collaborative price index.
 *
 * <strong>LGPD-critical:</strong> this entity intentionally does NOT carry
 * user_id or household_id. Any link back to a contributor lives in the
 * separate, internal-only PriceObservationAudit table. Public aggregate
 * queries are k-anonymity-guarded at the query layer.
 */
@Entity
@Table(name = "price_observations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PriceObservation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "market_cnpj", nullable = false, length = 14)
    private String marketCnpj;

    /** First 8 digits of the CNPJ — identifies the chain (e.g., all Zaffari units share a root). */
    @Column(name = "market_cnpj_root", nullable = false, length = 8)
    private String marketCnpjRoot;

    @Column(name = "market_name", length = 255)
    private String marketName;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 4)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "pack_size", precision = 10, scale = 3)
    private BigDecimal packSize;

    @Column(name = "pack_unit", length = 10)
    private String packUnit;

    @Column(name = "observed_at", nullable = false)
    private LocalDateTime observedAt;

    @Column(name = "is_outlier", nullable = false)
    @lombok.Builder.Default
    private boolean outlier = false;
}
