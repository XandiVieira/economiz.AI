package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.enums.NotificationChannel;
import com.relyon.economizai.model.enums.NotificationType;

public record NotificationPreferenceResponse(
        NotificationType type,
        NotificationChannel channel
) {}
