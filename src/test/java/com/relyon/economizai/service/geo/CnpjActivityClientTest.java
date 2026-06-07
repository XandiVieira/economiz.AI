package com.relyon.economizai.service.geo;

import com.relyon.economizai.model.enums.MerchantSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CnpjActivityClientTest {

    @Test
    void primaryPharmacyCnaeWins() {
        assertEquals(MerchantSegment.PHARMACY,
                CnpjActivityClient.segmentFromCnae(List.of("4771701", "4729602")));
    }

    @Test
    void supermarketCnaes() {
        assertEquals(MerchantSegment.SUPERMARKET, CnpjActivityClient.segmentFromCnae(List.of("4711301")));
        assertEquals(MerchantSegment.SUPERMARKET, CnpjActivityClient.segmentFromCnae(List.of("4712100")));
    }

    @Test
    void pharmacyInSecondaryCnaeStillCounts() {
        // primary is something else, but a 4771 secondary marks it a pharmacy
        assertEquals(MerchantSegment.PHARMACY,
                CnpjActivityClient.segmentFromCnae(List.of("4729602", "4771701")));
    }

    @Test
    void otherActivityIsOther() {
        assertEquals(MerchantSegment.OTHER, CnpjActivityClient.segmentFromCnae(List.of("5611201")));
    }

    @Test
    void emptyIsUnknown() {
        assertEquals(MerchantSegment.UNKNOWN, CnpjActivityClient.segmentFromCnae(List.of()));
    }
}
