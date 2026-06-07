package com.relyon.economizai.service.geo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class NominatimGeocoderTest {

    private NominatimGeocoder geocoder;

    @BeforeEach
    void setUp() {
        // 1ms timeout so the live Nominatim call fails fast and we never hit the
        // network in CI — we only care about URI building, not the HTTP round-trip.
        geocoder = new NominatimGeocoder(RestClient.builder(), "test-agent", 1);
    }

    @Test
    void geocode_addressWithSpacesAndCommas_doesNotThrow_returnsEmptyOnFailure() {
        // Regression: a raw address with spaces previously threw
        // IllegalArgumentException ("Invalid character ' ' for QUERY_PARAM")
        // at URI-build time (before the try/catch), escaping geocode() entirely
        // and crashing the scheduled geocodePending job. The fix (build() instead
        // of build(true)) percent-encodes the query, so building never throws and
        // any transport failure is swallowed into Optional.empty().
        var address = "AV JUCA BATISTA , 1305 , , CAVALHADA , PORTO ALEGRE , RS, Brasil";

        assertThatCode(() -> {
            var result = geocoder.geocode(address);
            assertThat(result).isEmpty(); // empty because the 1ms call fails, NOT because it threw
        }).doesNotThrowAnyException();
    }

    @Test
    void geocode_blankAddress_returnsEmpty() {
        assertThat(geocoder.geocode("  ")).isEmpty();
    }
}
