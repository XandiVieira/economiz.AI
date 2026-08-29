package com.relyon.economizai.exception;

public class OcrUnavailableException extends DomainException {

    public OcrUnavailableException() {
        super("receipt.ocr.unavailable");
    }
}
