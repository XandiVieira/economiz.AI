package com.relyon.economizai.exception;

public class PriceAlertNotFoundException extends DomainException {

    public PriceAlertNotFoundException() {
        super("pricealert.not.found");
    }
}
