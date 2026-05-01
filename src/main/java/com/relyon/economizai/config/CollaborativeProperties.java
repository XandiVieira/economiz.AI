package com.relyon.economizai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "economizai")
public class CollaborativeProperties {

    private final Collaborative collaborative = new Collaborative();
    private final PersonalPromo personalPromo = new PersonalPromo();

    public Collaborative getCollaborative() { return collaborative; }
    public PersonalPromo getPersonalPromo() { return personalPromo; }

    public static class Collaborative {
        private boolean enabled = true;
        private int minHouseholdsForPublic = 3;
        private int minObservationsPerProductMarket = 5;
        private int minObservationsForCommunityPromo = 10;
        private int communityPromoThresholdPct = 15;
        private int lookbackDays = 90;

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
    }

    public static class PersonalPromo {
        private int thresholdPct = 10;
        private int minPurchasesForBaseline = 3;

        public int getThresholdPct() { return thresholdPct; }
        public void setThresholdPct(int v) { this.thresholdPct = v; }
        public int getMinPurchasesForBaseline() { return minPurchasesForBaseline; }
        public void setMinPurchasesForBaseline(int v) { this.minPurchasesForBaseline = v; }
    }
}
