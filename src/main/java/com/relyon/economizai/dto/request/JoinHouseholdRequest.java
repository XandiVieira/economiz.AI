package com.relyon.economizai.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JoinHouseholdRequest(
        @NotBlank @Size(min = 6, max = 8) String inviteCode
) {}
