package com.relyon.economizai.exception;

public class ReceiptParseException extends DomainException {

    public ReceiptParseException(String reason) {
        super("receipt.parse.failed", reason);
    }
}
