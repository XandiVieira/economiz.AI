package com.relyon.economizai.dto.request;

import jakarta.validation.constraints.NotNull;

public record UpdateContributionRequest(
        @NotNull Boolean contributionOptIn
) {}
