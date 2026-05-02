package com.relyon.economizai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record UpdateReceiptItemRequest(
        @Schema(description = "Item text exactly as parsed from the NFC-e — fix typos here before confirming.",
                example = "ARROZ TIO J TP1 5KG")
        @NotBlank @Size(max = 500) String rawDescription,
        @Schema(description = "EAN barcode (digits only). Optional — leave null if the receipt didn't carry one.",
                example = "7891234567890")
        @Size(max = 14) String ean,
        @Schema(description = "Quantity purchased.", example = "3")
        @NotNull @DecimalMin(value = "0.001") BigDecimal quantity,
        @Schema(description = "Sales unit (UN, KG, L, …).", example = "UN")
        @Size(max = 10) String unit,
        @Schema(description = "Price per unit. Optional if you only edit the total.")
        @DecimalMin(value = "0.0") BigDecimal unitPrice,
        @Schema(description = "Total paid for this line. Should equal quantity × unitPrice for the math to check out.")
        @NotNull @DecimalMin(value = "0.0") BigDecimal totalPrice,
        @Schema(description = "Optional. true = exclude this item from spend / consumption / price-index. " +
                "Useful for items that belong to someone outside the household. Omit to leave the flag unchanged.")
        Boolean excluded
) {}
