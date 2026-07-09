package com.relyon.economizai.model.enums;

/**
 * A metered external service that costs money per call. Used as the ledger key
 * in {@code paid_api_call} and the dimension for per-user daily caps and the
 * Infosimples circuit breaker.
 */
public enum PaidApiService {

    /** Infosimples SEFAZ-by-chave query (~R$0.24). CE every note + fallback everywhere. */
    INFOSIMPLES(24),

    /** A single captcha solve via CapSolver/2Captcha (~R$0.03). Every scrape solves at least one. */
    CAPTCHA_SOLVE(3);

    private final int defaultCostCents;

    PaidApiService(int defaultCostCents) {
        this.defaultCostCents = defaultCostCents;
    }

    public int defaultCostCents() {
        return defaultCostCents;
    }
}
