package com.relyon.economizai.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank String name,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 100) String password,
        @NotBlank String acceptedTermsVersion,
        @NotBlank String acceptedPrivacyVersion
) {

    @AssertTrue(message = "Você deve aceitar a versão atual dos Termos de Uso e da Política de Privacidade")
    public boolean isLegalAcceptanceValid() {
        return acceptedTermsVersion != null && !acceptedTermsVersion.isBlank()
                && acceptedPrivacyVersion != null && !acceptedPrivacyVersion.isBlank();
    }
}
