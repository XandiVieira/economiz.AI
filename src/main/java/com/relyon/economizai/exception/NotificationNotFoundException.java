package com.relyon.economizai.exception;

public class NotificationNotFoundException extends DomainException {

    public NotificationNotFoundException() {
        super("notification.not.found");
    }
}
