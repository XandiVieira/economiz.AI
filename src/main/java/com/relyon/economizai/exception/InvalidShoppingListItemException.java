package com.relyon.economizai.exception;

public class InvalidShoppingListItemException extends DomainException {

    public InvalidShoppingListItemException() {
        super("shopping.list.item.required");
    }
}
