package com.relyon.economizai.dto.request;

import com.relyon.economizai.model.enums.Platform;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record AppleLoginRequest(
        @Schema(description = "Apple identity token (JWT) obtained natively by the mobile app via Sign in with Apple. "
                + "Verified server-side against Apple's JWKS.")
        @NotBlank String identityToken,

        @Schema(description = "Display name. Apple only returns the user's name on the FIRST authorization, so the app "
                + "must forward it here; it can be null on subsequent sign-ins.")
        String name,

        @Schema(description = "Optional client platform (WEB / ANDROID / IOS). Recorded as the last-login "
                + "platform. Unknown/absent values are ignored.", example = "IOS")
        Platform platform
) {}
