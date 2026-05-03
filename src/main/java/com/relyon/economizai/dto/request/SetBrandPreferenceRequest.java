package com.relyon.economizai.dto.request;

import com.relyon.economizai.dto.response.HouseholdPreferenceResponse.BrandStrength;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SetBrandPreferenceRequest(
        @NotBlank String brand,
        @NotNull BrandStrength strength
) {}
