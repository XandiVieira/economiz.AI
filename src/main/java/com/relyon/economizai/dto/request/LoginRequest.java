package com.relyon.economizai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @Schema(example = "maria@example.com") @NotBlank @Email String email,
        @Schema(description = "Plain-text password — sent over HTTPS, BCrypt-compared server-side.")
        @NotBlank String password
) {}
