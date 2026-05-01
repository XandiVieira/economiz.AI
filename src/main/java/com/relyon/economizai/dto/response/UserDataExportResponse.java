package com.relyon.economizai.dto.response;

import java.time.LocalDateTime;
import java.util.List;

public record UserDataExportResponse(
        UserResponse user,
        HouseholdResponse household,
        List<ReceiptResponse> receipts,
        LocalDateTime exportedAt
) {}
