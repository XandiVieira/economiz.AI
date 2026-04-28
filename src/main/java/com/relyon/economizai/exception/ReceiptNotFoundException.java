package com.relyon.economizai.exception;

public class ReceiptNotFoundException extends DomainException {

    public ReceiptNotFoundException() {
        super("receipt.not.found");
    }
}
