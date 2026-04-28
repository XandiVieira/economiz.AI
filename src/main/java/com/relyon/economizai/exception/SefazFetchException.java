package com.relyon.economizai.exception;

public class SefazFetchException extends DomainException {

    public SefazFetchException(String state) {
        super("receipt.sefaz.fetch.failed", state);
    }
}
