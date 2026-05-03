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

@Entity
@Table(name = "receipt_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class ReceiptItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_id", nullable = false)
    private Receipt receipt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    @Column(name = "raw_description", nullable = false, columnDefinition = "TEXT")
    private String rawDescription;

    @Column(name = "friendly_description", length = 500)
    private String friendlyDescription;

    @Column(length = 14)
    private String ean;

    @Column(precision = 12, scale = 3, nullable = false)
    private BigDecimal quantity;

    @Column(length = 10)
    private String unit;

    @Column(name = "unit_price", precision = 12, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "total_price", precision = 12, scale = 2, nullable = false)
    private BigDecimal totalPrice;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean excluded = false;

    /**
     * True when the SEFAZ HTML signaled this item was on promo / discount.
     * Surfaces in the user's history ("você pegou uma oferta") and tells
     * the price-index pipeline NOT to use this row as a baseline price.
     */
    @Column(name = "nfce_promo_flag", nullable = false)
    @lombok.Builder.Default
    private boolean nfcePromoFlag = false;
}
