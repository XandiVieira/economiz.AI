package com.relyon.economizai.dto.request;

import com.relyon.economizai.model.enums.ProductCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProductRequest(
        @NotBlank @Size(max = 255) String normalizedName,
        @Size(max = 100) String brand,
        ProductCategory category,
        @Size(max = 10) String unit
) {}
