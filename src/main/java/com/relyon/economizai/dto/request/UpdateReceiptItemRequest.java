package com.relyon.economizai.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record UpdateReceiptItemRequest(
        @NotBlank @Size(max = 500) String rawDescription,
        @Size(max = 14) String ean,
        @NotNull @DecimalMin(value = "0.001") BigDecimal quantity,
        @Size(max = 10) String unit,
        @DecimalMin(value = "0.0") BigDecimal unitPrice,
        @NotNull @DecimalMin(value = "0.0") BigDecimal totalPrice
) {}
