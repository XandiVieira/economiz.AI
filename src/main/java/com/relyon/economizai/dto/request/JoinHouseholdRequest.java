package com.relyon.economizai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JoinHouseholdRequest(
        @Schema(description = "6-character invite code from another household. Case-insensitive. " +
                "Codes expire 48h after generation; use POST /households/me/invite-code/regenerate to rotate.",
                example = "ABC123")
        @NotBlank @Size(min = 6, max = 8) String inviteCode
) {}
