package com.relyon.economizai.exception;

public class InvalidOAuthTokenException extends DomainException {

    public InvalidOAuthTokenException() {
        super("auth.oauth.invalid");
    }
}
