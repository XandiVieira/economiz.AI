package com.relyon.economizai.exception;

public class InvalidCnpjException extends DomainException {

    public InvalidCnpjException(String reasonKey, String... arguments) {
        super(reasonKey, arguments);
    }
}
