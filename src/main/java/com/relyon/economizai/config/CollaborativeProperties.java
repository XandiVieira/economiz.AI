package com.relyon.economizai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "economizai")
public class CollaborativeProperties {

    private final Collaborative collaborative = new Collaborative();
    private final PersonalPromo personalPromo = new PersonalPromo();
    private final Consumption consumption = new Consumption();
    private final Preferences preferences = new Preferences();
    private final Subscription subscription = new Subscription();

    public Collaborative getCollaborative() { return collaborative; }
    public PersonalPromo getPersonalPromo() { return personalPromo; }
    public Consumption getConsumption() { return consumption; }
    public Preferences getPreferences() { return preferences; }
    public Subscription getSubscription() { return subscription; }

    /**
     * FREE-tier limits, tunable so paywall thresholds can move without code
     * changes. PRO bypasses all of these (treated as unlimited).
     */
    public static class Subscription {
        private final Free free = new Free();

        public Free getFree() { return free; }

        public static class Free {
            /** Max pinned watched markets for FREE users. */
            private int watchedMarkets = 3;
            /** Rolling history/analytics window (days) for FREE users. */
            private int historyDays = 90;
            /** Max receipts a FREE user may submit per calendar month. */
            private int monthlyReceipts = 5;

            public int getWatchedMarkets() { return watchedMarkets; }
            public void setWatchedMarkets(int v) { this.watchedMarkets = v; }
            public int getHistoryDays() { return historyDays; }
            public void setHistoryDays(int v) { this.historyDays = v; }
            public int getMonthlyReceipts() { return monthlyReceipts; }
            public void setMonthlyReceipts(int v) { this.monthlyReceipts = v; }
        }
    }

    public static class Collaborative {
        private boolean enabled = true;
        /**
         * k-anonymity threshold (K). The minimum number of DISTINCT households that
         * must have contributed before a (product, market) price may be disclosed to
         * anyone other than the contributor.
         *
         * <p><strong>Why this rule exists:</strong> {@code PriceObservation} is
         * anonymized on purpose — it carries no user/household FK. But a per-market
         * price disclosed from a market with only one contributor effectively reveals
         * THAT contributor's purchase (what, where, roughly when) — re-identifying a
         * person from "anonymous" data. Requiring K≥3 distinct households means no
         * single household's purchase can be inferred from a disclosed price/median.
         * A household always sees its OWN prices at full detail (own data, no K needed);
         * K only gates other households' data. Configurable so the rule can be relaxed
         * later if the privacy/legal stance changes, without touching code.
         */
        private int minHouseholdsForPublic = 3;
        private int minObservationsPerProductMarket = 5;
        private int minObservationsForCommunityPromo = 10;
        private int communityPromoThresholdPct = 15;
        private int lookbackDays = 90;
        private int communityPromoRecentWindowDays = 7;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMinHouseholdsForPublic() { return minHouseholdsForPublic; }
        public void setMinHouseholdsForPublic(int v) { this.minHouseholdsForPublic = v; }
        public int getMinObservationsPerProductMarket() { return minObservationsPerProductMarket; }
        public void setMinObservationsPerProductMarket(int v) { this.minObservationsPerProductMarket = v; }
        public int getMinObservationsForCommunityPromo() { return minObservationsForCommunityPromo; }
        public void setMinObservationsForCommunityPromo(int v) { this.minObservationsForCommunityPromo = v; }
        public int getCommunityPromoThresholdPct() { return communityPromoThresholdPct; }
        public void setCommunityPromoThresholdPct(int v) { this.communityPromoThresholdPct = v; }
        public int getLookbackDays() { return lookbackDays; }
        public void setLookbackDays(int v) { this.lookbackDays = v; }
        public int getCommunityPromoRecentWindowDays() { return communityPromoRecentWindowDays; }
        public void setCommunityPromoRecentWindowDays(int v) { this.communityPromoRecentWindowDays = v; }
    }

    public static class PersonalPromo {
        private int thresholdPct = 10;
        private int minPurchasesForBaseline = 3;

        public int getThresholdPct() { return thresholdPct; }
        public void setThresholdPct(int v) { this.thresholdPct = v; }
        public int getMinPurchasesForBaseline() { return minPurchasesForBaseline; }
        public void setMinPurchasesForBaseline(int v) { this.minPurchasesForBaseline = v; }
    }

    /**
     * Consumption-intelligence gates (Phase 3). Predictions are noisy with
     * little history — these thresholds keep us from showing low-confidence
     * estimates until the household has enough purchase data.
     */
    public static class Consumption {
        private boolean enabled = true;
        private int minPurchasesForPrediction = 2;
        private int historyLookbackDays = 365;
        private int runningLowThresholdDays = 7;
        private int ranOutGraceDays = 0;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getMinPurchasesForPrediction() { return minPurchasesForPrediction; }
        public void setMinPurchasesForPrediction(int v) { this.minPurchasesForPrediction = v; }
        public int getHistoryLookbackDays() { return historyLookbackDays; }
        public void setHistoryLookbackDays(int v) { this.historyLookbackDays = v; }
        public int getRunningLowThresholdDays() { return runningLowThresholdDays; }
        public void setRunningLowThresholdDays(int v) { this.runningLowThresholdDays = v; }
        public int getRanOutGraceDays() { return ranOutGraceDays; }
        public void setRanOutGraceDays(int v) { this.ranOutGraceDays = v; }
    }

    /**
     * Phase 2.6 — household pack-size + brand preferences. Auto-derived
     * from purchase history; no manual override yet (planned PRO feature).
     * Volume-gated so we stay quiet until a household has enough data
     * to make derivation meaningful.
     */
    public static class Preferences {
        private boolean enabled = true;
        private int minPurchasesPerGeneric = 5;
        private double mustHaveBrandShare = 0.85;
        private double preferredBrandShare = 0.60;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getMinPurchasesPerGeneric() { return minPurchasesPerGeneric; }
        public void setMinPurchasesPerGeneric(int v) { this.minPurchasesPerGeneric = v; }
        public double getMustHaveBrandShare() { return mustHaveBrandShare; }
        public void setMustHaveBrandShare(double v) { this.mustHaveBrandShare = v; }
        public double getPreferredBrandShare() { return preferredBrandShare; }
        public void setPreferredBrandShare(double v) { this.preferredBrandShare = v; }
    }
}
