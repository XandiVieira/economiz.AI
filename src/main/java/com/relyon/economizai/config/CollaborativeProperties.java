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

    public Collaborative getCollaborative() { return collaborative; }
    public PersonalPromo getPersonalPromo() { return personalPromo; }
    public Consumption getConsumption() { return consumption; }
    public Preferences getPreferences() { return preferences; }

    public static class Collaborative {
        private boolean enabled = true;
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
        private int minPurchasesForPrediction = 3;
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
