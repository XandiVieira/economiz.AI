package com.relyon.economizai.exception;

public class InvalidCategoryMigrationException extends DomainException {
    public InvalidCategoryMigrationException() {
        super("customcategory.migration.invalid");
    }
}
