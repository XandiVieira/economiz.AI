package com.relyon.economizai.model.enums;

public enum ReceiptStatus {
    /** Submitted; SEFAZ fetch + parse (incl. captcha solve) running in the background. */
    PROCESSING,
    PENDING_CONFIRMATION,
    CONFIRMED,
    REJECTED,
    FAILED_PARSE
}
