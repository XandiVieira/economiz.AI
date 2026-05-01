package com.relyon.economizai.dto.request;

import jakarta.validation.constraints.Size;

public record UpdatePushTokenRequest(
        @Size(max = 500) String pushDeviceToken  // null/empty clears the token
) {}
