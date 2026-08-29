package com.relyon.economizai.exception;

public class NotInHouseholdException extends DomainException {

    public NotInHouseholdException() {
        super("household.not.member");
    }
}
