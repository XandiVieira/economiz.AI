package com.relyon.economizai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @Schema(description = "Display name shown across the app and on shared household views.", example = "Maria Silva")
        @NotBlank String name,
        @Schema(description = "Login identifier. Must be unique across the system.", example = "maria@example.com")
        @NotBlank @Email String email,
        @Schema(description = "8+ chars. Plain text on the wire — sent over HTTPS, hashed (BCrypt) at rest.")
        @NotBlank @Size(min = 8, max = 100) String password,
        @Schema(description = "Version string of the Terms of Use the user accepted (fetch current via GET /legal/terms). " +
                "Backend rejects stale versions with 400.", example = "1.0")
        @NotBlank String acceptedTermsVersion,
        @Schema(description = "Version string of the Privacy Policy the user accepted (fetch current via GET /legal/privacy-policy).",
                example = "1.0")
        @NotBlank String acceptedPrivacyVersion
) {

    @AssertTrue(message = "Você deve aceitar a versão atual dos Termos de Uso e da Política de Privacidade")
    public boolean isLegalAcceptanceValid() {
        return acceptedTermsVersion != null && !acceptedTermsVersion.isBlank()
                && acceptedPrivacyVersion != null && !acceptedPrivacyVersion.isBlank();
    }
}
