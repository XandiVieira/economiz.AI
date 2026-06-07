package com.relyon.economizai.service.subscription;

import com.relyon.economizai.config.CollaborativeProperties;
import com.relyon.economizai.exception.PaywallException;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.SubscriptionTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionGateServiceTest {

    private SubscriptionGateService gate;

    @BeforeEach
    void setUp() {
        var properties = new CollaborativeProperties();
        // defaults: watchedMarkets=3, historyDays=90, monthlyReceipts=5
        gate = new SubscriptionGateService(properties);
    }

    private User user(SubscriptionTier tier) {
        return User.builder().id(UUID.randomUUID()).email("u@e").subscriptionTier(tier).build();
    }

    @Test
    void allows_proGetsEveryFeature() {
        var pro = user(SubscriptionTier.PRO);
        for (var feature : Feature.values()) {
            assertTrue(gate.allows(pro, feature), "PRO should allow " + feature);
        }
    }

    @Test
    void allows_freeBlockedFromEveryFeature() {
        var free = user(SubscriptionTier.FREE);
        for (var feature : Feature.values()) {
            assertFalse(gate.allows(free, feature), "FREE should be blocked from " + feature);
        }
    }

    @Test
    void require_throwsForFreeAndPassesForPro() {
        assertThrows(PaywallException.class,
                () -> gate.require(user(SubscriptionTier.FREE), Feature.BASKET_OPTIMIZATION));
        // PRO: no exception
        gate.require(user(SubscriptionTier.PRO), Feature.BASKET_OPTIMIZATION);
    }

    @Test
    void watchedMarketLimit_freeUsesConfigProUnlimited() {
        assertEquals(3, gate.watchedMarketLimit(user(SubscriptionTier.FREE)));
        assertEquals(Integer.MAX_VALUE, gate.watchedMarketLimit(user(SubscriptionTier.PRO)));
    }

    @Test
    void monthlyReceiptLimit_freeUsesConfigProUnlimited() {
        assertEquals(5, gate.monthlyReceiptLimit(user(SubscriptionTier.FREE)));
        assertEquals(Integer.MAX_VALUE, gate.monthlyReceiptLimit(user(SubscriptionTier.PRO)));
    }

    @Test
    void freeHistoryWindowDays_freeUsesConfigProNull() {
        assertEquals(90, gate.freeHistoryWindowDays(user(SubscriptionTier.FREE)));
        assertNull(gate.freeHistoryWindowDays(user(SubscriptionTier.PRO)));
    }

    @Test
    void clampFrom_proIsUnaffected() {
        var pro = user(SubscriptionTier.PRO);
        var old = LocalDateTime.now().minusYears(3);
        assertEquals(old, gate.clampFrom(pro, old));
        assertNull(gate.clampFrom(pro, null));
    }

    @Test
    void clampFrom_freeFloorsEarlierRequests() {
        var free = user(SubscriptionTier.FREE);
        var floor = LocalDateTime.now().minusDays(90);

        var clampedOld = gate.clampFrom(free, LocalDateTime.now().minusYears(2));
        assertFalse(clampedOld.isBefore(floor.minusSeconds(2)));
        assertTrue(clampedOld.isAfter(floor.minusSeconds(2)));

        // Null request (unbounded) is floored to the window.
        var clampedNull = gate.clampFrom(free, null);
        assertTrue(clampedNull.isAfter(floor.minusSeconds(2)));
    }

    @Test
    void clampFrom_freeKeepsRecentRequestWithinWindow() {
        var free = user(SubscriptionTier.FREE);
        var recent = LocalDateTime.now().minusDays(10);
        assertEquals(recent, gate.clampFrom(free, recent));
    }
}
