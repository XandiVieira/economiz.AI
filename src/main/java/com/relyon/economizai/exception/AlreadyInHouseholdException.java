package com.relyon.economizai.exception;

public class AlreadyInHouseholdException extends DomainException {

    public AlreadyInHouseholdException() {
        super("household.already.member");
    }
}
