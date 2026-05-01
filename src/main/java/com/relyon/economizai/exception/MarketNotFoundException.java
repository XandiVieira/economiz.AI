package com.relyon.economizai.exception;

public class MarketNotFoundException extends DomainException {

    public MarketNotFoundException(String cnpj) {
        super("market.not.found", cnpj);
    }
}
