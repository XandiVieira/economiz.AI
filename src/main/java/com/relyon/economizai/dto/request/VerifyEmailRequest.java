package com.relyon.economizai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record VerifyEmailRequest(
        @Schema(description = "Token from the verification link. Single-use, expires in 24 hours.")
        @NotBlank String token
) {}
