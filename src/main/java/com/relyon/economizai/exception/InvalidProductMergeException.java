package com.relyon.economizai.exception;

public class InvalidProductMergeException extends DomainException {

    public InvalidProductMergeException(String reason) {
        super("product.merge.invalid", reason);
    }
}
