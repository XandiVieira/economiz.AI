package com.relyon.economizai.exception;

public class ProductNotFoundException extends DomainException {

    public ProductNotFoundException() {
        super("product.not.found");
    }
}
