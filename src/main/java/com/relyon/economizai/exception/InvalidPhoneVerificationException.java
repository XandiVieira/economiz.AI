package com.relyon.economizai.exception;

public class InvalidPhoneVerificationException extends DomainException {

    public InvalidPhoneVerificationException() {
        super("user.phone.verification.invalid");
    }
}
