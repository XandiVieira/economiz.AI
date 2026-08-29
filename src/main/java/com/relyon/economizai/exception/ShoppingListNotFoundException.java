package com.relyon.economizai.exception;

public class ShoppingListNotFoundException extends DomainException {

    public ShoppingListNotFoundException() {
        super("shopping.list.not.found");
    }
}
