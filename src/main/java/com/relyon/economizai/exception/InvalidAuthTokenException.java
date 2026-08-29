package com.relyon.economizai.exception;

public class InvalidAuthTokenException extends DomainException {

    public InvalidAuthTokenException() {
        super("auth.token.invalid");
    }
}
