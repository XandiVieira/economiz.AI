package com.relyon.economizai.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAliasRequest(
        @NotBlank @Size(max = 500) String rawDescription
) {}
