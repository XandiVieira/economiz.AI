package com.relyon.economizai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @Schema(description = "Token from the reset link. Single-use, expires in 60 minutes.")
        @NotBlank String token,
        @Schema(description = "New password (8+ chars).")
        @NotBlank @Size(min = 8, max = 100) String newPassword
) {}
