package com.relyon.economizai.dto.response;

/** Outcome of migrating selected products into a category. */
public record CategoryMigrationResponse(int migrated, int skipped) {}
