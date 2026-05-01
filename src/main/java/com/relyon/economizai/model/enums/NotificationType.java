package com.relyon.economizai.model.enums;

public enum NotificationType {
    PROMO_PERSONAL,    // user paid less than usual on a recent receipt
    PROMO_COMMUNITY,   // a product the user has bought before went on sale somewhere nearby
    STOCKOUT,          // a product the user buys regularly is predicted to run out soon (Phase 3)
    SYSTEM             // generic system messages (welcome, policy update, etc.)
}
