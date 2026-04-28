package com.relyon.economizai.exception;

public class HouseholdNotFoundException extends DomainException {

    public HouseholdNotFoundException() {
        super("household.not.found");
    }
}
