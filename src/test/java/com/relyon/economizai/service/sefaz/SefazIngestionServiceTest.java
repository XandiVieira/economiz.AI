package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.InvalidQrPayloadException;
import com.relyon.economizai.exception.UnsupportedStateException;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import com.relyon.economizai.service.sefaz.SefazIngestionService.FetchedDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SefazIngestionServiceTest {

    // UF code 43 → RS.
    private static final String CHAVE_RS = "43260412345678000190650010000123451123456780";
    // UF code 35 → SP.
    private static final String CHAVE_SP = "35260412345678000190650010000123451123456780";

    /** A simple in-test adapter so the constructor's duplicate guard can be exercised. */
    private static class FakeAdapter implements SefazAdapter {
        private final Set<UnidadeFederativa> states;

        FakeAdapter(Set<UnidadeFederativa> states) {
            this.states = states;
        }

        @Override
        public Set<UnidadeFederativa> supportedStates() {
            return states;
        }

        @Override
        public String fetchHtml(String qrPayload) {
            return "<html>fake</html>";
        }

        @Override
        public ParsedReceipt parseHtml(String html, String chaveAcesso, String sourceUrl) {
            return null;
        }
    }

    private SefazAdapter mockAdapterFor(UnidadeFederativa... ufs) {
        var adapter = mock(SefazAdapter.class);
        when(adapter.supportedStates()).thenReturn(Set.of(ufs));
        return adapter;
    }

    private ParsedReceipt sampleParsed() {
        return ParsedReceipt.builder()
                .chaveAcesso(CHAVE_RS)
                .cnpjEmitente("12345678000190")
                .marketName("ZAFFARI")
                .issuedAt(LocalDateTime.now())
                .totalAmount(new BigDecimal("99.48"))
                .rawHtml("<html>raw</html>")
                .items(List.of())
                .build();
    }

    @Test
    void constructor_registersAllSupportedStates() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS, UnidadeFederativa.SC);
        var spAdapter = mockAdapterFor(UnidadeFederativa.SP);

        var service = new SefazIngestionService(List.of(rsAdapter, spAdapter));

        // RS routes to the RS adapter via fetch.
        when(rsAdapter.fetchHtml(anyString())).thenReturn("<html>rs</html>");
        var fetched = service.fetch(CHAVE_RS);
        assertSame(rsAdapter, fetched.adapter());
        assertEquals(UnidadeFederativa.RS, fetched.uf());
    }

    @Test
    void constructor_throwsWhenTwoAdaptersClaimSameUf() {
        var first = new FakeAdapter(Set.of(UnidadeFederativa.RS));
        var second = new FakeAdapter(Set.of(UnidadeFederativa.RS, UnidadeFederativa.SC));

        var error = assertThrows(IllegalStateException.class,
                () -> new SefazIngestionService(List.of(first, second)));
        assertTrue(error.getMessage().contains("RS"));
    }

    @Test
    void constructor_sameAdapterInstanceListedTwiceIsNotADuplicate() {
        var shared = mockAdapterFor(UnidadeFederativa.RS);

        // Same instance reappearing must not trip the guard (prior == adapter).
        var service = new SefazIngestionService(List.of(shared, shared));

        when(shared.fetchHtml(anyString())).thenReturn("<html>ok</html>");
        var fetched = service.fetch(CHAVE_RS);
        assertSame(shared, fetched.adapter());
    }

    @Test
    void fetch_picksAdapterByUfSanitizesCpfAndSetsSourceUrlForHttp() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var httpPayload = "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=" + CHAVE_RS;
        when(rsAdapter.fetchHtml(httpPayload))
                .thenReturn("<html>cliente 123.456.789-00 fim</html>");
        var service = new SefazIngestionService(List.of(rsAdapter));

        var fetched = service.fetch(httpPayload);

        assertSame(rsAdapter, fetched.adapter());
        assertEquals(CHAVE_RS, fetched.chave());
        assertEquals(UnidadeFederativa.RS, fetched.uf());
        assertEquals(httpPayload, fetched.sourceUrl());
        assertTrue(fetched.html().contains("***.***.***-**"));
        assertTrue(!fetched.html().contains("123.456.789-00"));
    }

    @Test
    void fetch_setsNullSourceUrlForNonHttpPayload() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.fetchHtml(anyString())).thenReturn("<html>ok</html>");
        var service = new SefazIngestionService(List.of(rsAdapter));

        var fetched = service.fetch(CHAVE_RS);

        assertNull(fetched.sourceUrl());
    }

    @Test
    void fetch_throwsUnsupportedStateWhenNoAdapterForUf() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var service = new SefazIngestionService(List.of(rsAdapter));

        assertThrows(UnsupportedStateException.class, () -> service.fetch(CHAVE_SP));
    }

    @Test
    void fetch_throwsInvalidQrPayloadForGarbageInput() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var service = new SefazIngestionService(List.of(rsAdapter));

        assertThrows(InvalidQrPayloadException.class, () -> service.fetch("not-a-chave"));
    }

    @Test
    void parse_delegatesToFetchedAdapter() {
        var rsAdapter = mock(SefazAdapter.class);
        var fetched = new FetchedDocument(rsAdapter, "<html>x</html>", CHAVE_RS,
                UnidadeFederativa.RS, "https://example/src");
        var expected = sampleParsed();
        when(rsAdapter.parseHtml("<html>x</html>", CHAVE_RS, "https://example/src"))
                .thenReturn(expected);

        var service = new SefazIngestionService(List.of(mockAdapterFor(UnidadeFederativa.RS)));
        var parsed = service.parse(fetched);

        assertSame(expected, parsed);
    }

    @Test
    void ingest_fetchesThenParses() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.fetchHtml(anyString())).thenReturn("<html>raw</html>");
        var expected = sampleParsed();
        when(rsAdapter.parseHtml(eq("<html>raw</html>"), eq(CHAVE_RS), any()))
                .thenReturn(expected);
        var service = new SefazIngestionService(List.of(rsAdapter));

        var parsed = service.ingest(CHAVE_RS);

        assertSame(expected, parsed);
        verify(rsAdapter).fetchHtml(CHAVE_RS);
    }

    @Test
    void reparseStored_usesAdapterForUf() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var expected = sampleParsed();
        when(rsAdapter.parseHtml("<html>stored</html>", CHAVE_RS, "src"))
                .thenReturn(expected);
        var service = new SefazIngestionService(List.of(rsAdapter));

        var parsed = service.reparseStored(UnidadeFederativa.RS, "<html>stored</html>", CHAVE_RS, "src");

        assertSame(expected, parsed);
    }

    @Test
    void reparseStored_throwsUnsupportedStateWhenNoAdapter() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var service = new SefazIngestionService(List.of(rsAdapter));

        assertThrows(UnsupportedStateException.class,
                () -> service.reparseStored(UnidadeFederativa.SP, "<html>x</html>", CHAVE_SP, null));
    }
}
