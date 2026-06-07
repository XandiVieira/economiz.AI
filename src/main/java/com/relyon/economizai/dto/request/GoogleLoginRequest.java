package com.relyon.economizai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record GoogleLoginRequest(
        @Schema(description = "Google ID token (JWT) obtained natively by the mobile app via Google Sign-In. "
                + "Verified server-side against Google's JWKS.")
        @NotBlank String idToken
) {}
