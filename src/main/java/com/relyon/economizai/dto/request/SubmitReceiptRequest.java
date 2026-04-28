package com.relyon.economizai.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmitReceiptRequest(
        @NotBlank @Size(max = 4000) String qrPayload
) {}
