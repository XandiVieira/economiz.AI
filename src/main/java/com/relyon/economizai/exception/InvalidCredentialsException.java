package com.relyon.economizai.exception;

public class InvalidCredentialsException extends DomainException {

    public InvalidCredentialsException() {
        super("auth.invalid.credentials");
    }
}
