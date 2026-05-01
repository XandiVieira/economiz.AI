package com.relyon.economizai.service.notifications;

import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.NotificationType;

import java.util.Map;

/**
 * What a service layer hands to NotificationService when something
 * worth telling the user about happens. Channel-agnostic — the
 * service decides how (email, push) based on user preference.
 */
public record NotificationPayload(
        User user,
        NotificationType type,
        String title,
        String body,
        Map<String, Object> extras
) {}
