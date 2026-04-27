package com.relyon.economizai.exception;

public class UserNotFoundException extends DomainException {

    public UserNotFoundException(String userId) {
        super("user.not.found", userId);
    }
}
