package com.relyon.economizai.exception;

public class ReceiptAlreadyIngestedException extends DomainException {

    public ReceiptAlreadyIngestedException(String chaveAcesso) {
        super("receipt.already.ingested", chaveAcesso);
    }
}
