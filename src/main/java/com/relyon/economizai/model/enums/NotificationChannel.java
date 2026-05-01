package com.relyon.economizai.model.enums;

public enum NotificationChannel {
    PUSH,    // mobile push (FCM via PushDispatcher; currently a stub that logs)
    EMAIL,   // SMTP via EmailDispatcher
    NONE     // user opted out of this notification type
}
