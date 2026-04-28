package com.relyon.economizai.exception;

public class UnsupportedStateException extends DomainException {

    public UnsupportedStateException(String state) {
        super("receipt.state.unsupported", state);
    }
}
