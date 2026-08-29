package com.relyon.economizai.exception;

public class CustomCategoryNotFoundException extends DomainException {
    public CustomCategoryNotFoundException() {
        super("customcategory.not.found");
    }
}
