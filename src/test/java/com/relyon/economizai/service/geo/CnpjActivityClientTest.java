package com.relyon.economizai.service.geo;

import com.relyon.economizai.model.enums.MerchantSegment;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CnpjActivityClientTest {

    private CnpjActivityClient client(boolean enabled) {
        // 1ms timeout: any reachable code path here never depends on the network.
        return new CnpjActivityClient(RestClient.builder(),
                "https://example.test/api/cnpj/v1", 1, 3, "test-agent", enabled);
    }

    @Test
    void isEnabled_reflectsConstructorFlag() {
        assertTrue(client(true).isEnabled());
        assertFalse(client(false).isEnabled());
    }

    @Test
    void classify_returnsUnknownWhenDisabled() {
        assertEquals(MerchantSegment.UNKNOWN, client(false).classify("11111111000111"));
    }

    @Test
    void classify_returnsUnknownWhenCnpjNull() {
        assertEquals(MerchantSegment.UNKNOWN, client(true).classify(null));
    }

    @Test
    void classify_returnsUnknownWhenCnpjBlank() {
        assertEquals(MerchantSegment.UNKNOWN, client(true).classify("   "));
    }

    @Test
    void classify_returnsUnknownWhenFetchFails() {
        // base-url points nowhere reachable + 1ms timeout → every attempt fails,
        // fetchWithRetry returns null, classify yields UNKNOWN (never throws).
        assertEquals(MerchantSegment.UNKNOWN, client(true).classify("11111111000111"));
    }

    @Test
    void constructor_clampsMaxAttemptsToAtLeastOne() {
        var clamped = new CnpjActivityClient(RestClient.builder(),
                "https://example.test/api/cnpj/v1/", 1, 0, "test-agent", true);
        // Trailing slash trimmed and maxAttempts >= 1: still reaches UNKNOWN safely.
        assertEquals(MerchantSegment.UNKNOWN, clamped.classify("11111111000111"));
    }

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

    /** Client whose HTTP seam returns a canned BrasilAPI body. */
    private CnpjActivityClient clientWithBody(String body) {
        return new CnpjActivityClient(RestClient.builder(),
                "https://example.test/api/cnpj/v1", 1, 3, "test-agent", true) {
            @Override protected String fetchWithRetry(String cnpj) {
                return body;
            }
        };
    }

    @Test
    void lookup_extractsSegmentAndIbgeCityCode() {
        var lookup = clientWithBody(
                "{\"cnae_fiscal\":\"4711301\",\"codigo_municipio_ibge\":4314902}")
                .lookup("11111111000111");
        assertEquals(MerchantSegment.SUPERMARKET, lookup.segment());
        assertEquals("4314902", lookup.ibgeCityCode());
    }

    @Test
    void lookup_nullIbgeWhenFieldMissingOrMalformed() {
        assertEquals(null, clientWithBody("{\"cnae_fiscal\":\"4711301\"}")
                .lookup("11111111000111").ibgeCityCode());
        // wrong length → discarded rather than stored corrupt
        assertEquals(null, clientWithBody("{\"codigo_municipio_ibge\":\"123\"}")
                .lookup("11111111000111").ibgeCityCode());
    }

    @Test
    void lookup_emptyOnUnparseableBody() {
        var lookup = clientWithBody("not-json").lookup("11111111000111");
        assertEquals(MerchantSegment.UNKNOWN, lookup.segment());
        assertEquals(null, lookup.ibgeCityCode());
    }

    @Test
    void classify_delegatesToLookup() {
        assertEquals(MerchantSegment.PHARMACY,
                clientWithBody("{\"cnae_fiscal\":\"4771701\"}").classify("11111111000111"));
    }
}
