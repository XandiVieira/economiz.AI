package com.relyon.economizai.model.enums;

import java.util.Arrays;
import java.util.List;

/**
 * The kind of a notification — doubles as the discriminator for a
 * {@link com.relyon.economizai.model.NotificationRule}. Each value carries
 * metadata that drives rule validation (does it need a product? a threshold?)
 * and default seeding (is it a system default the user merely toggles, or a
 * rule the user creates with parameters?).
 */
public enum NotificationType {

    // --- System defaults: auto-seeded per user, enabled by default, user can toggle ---
    /** You paid below your own historical median on a confirmed receipt. */
    PROMO_PERSONAL(Scope.DEFAULT, false, false),
    /** A product your household buys went on sale somewhere in the network. */
    PROMO_COMMUNITY(Scope.DEFAULT, false, false),
    /** The same product you buy is cheaper at a market near you. */
    CHEAPER_MARKET(Scope.DEFAULT, false, false),
    /** Periodic savings + watchlist digest. */
    DIGEST(Scope.DEFAULT, false, false),

    // --- User-created rules: the user supplies the parameters ---
    /** Notify when {@code product} is observed at or below {@code thresholdPrice}. */
    PRICE_DROP(Scope.USER, true, true),
    /** Notify when {@code product} is observed at or above {@code thresholdPrice}. */
    PRICE_ABOVE(Scope.USER, true, true),
    /** Replenishment: notify before a regularly-bought {@code product} is predicted to run out. */
    STOCKOUT(Scope.USER, true, false),
    /** Notify when household spend in the current month reaches {@code thresholdPrice} (R$). */
    BUDGET(Scope.USER, false, true),

    // --- Transactional / one-off ---
    SYSTEM(Scope.SYSTEM, false, false);

    public enum Scope { DEFAULT, USER, SYSTEM }

    private final Scope scope;
    private final boolean requiresProduct;
    private final boolean requiresThreshold;

    NotificationType(Scope scope, boolean requiresProduct, boolean requiresThreshold) {
        this.scope = scope;
        this.requiresProduct = requiresProduct;
        this.requiresThreshold = requiresThreshold;
    }

    public Scope scope() {
        return scope;
    }

    public boolean requiresProduct() {
        return requiresProduct;
    }

    public boolean requiresThreshold() {
        return requiresThreshold;
    }

    public boolean isDefault() {
        return scope == Scope.DEFAULT;
    }

    public boolean isUserCreatable() {
        return scope == Scope.USER;
    }

    /** Default types auto-seeded for every user (one toggleable rule each). */
    public static List<NotificationType> defaults() {
        return Arrays.stream(values()).filter(NotificationType::isDefault).toList();
    }
}
