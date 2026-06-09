package com.relyon.economizai.exception;

/**
 * Thrown when a client posts a notification event whose type is unknown or
 * server-only (not {@link com.relyon.economizai.model.enums.NotificationEventType#isClientReportable()}).
 */
public class InvalidNotificationEventException extends DomainException {

    public InvalidNotificationEventException(String type) {
        super("notification.event.type.invalid", type);
    }
}
