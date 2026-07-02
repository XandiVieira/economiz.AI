package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.CaptchaUnavailableException;
import com.relyon.economizai.exception.InvalidQrPayloadException;
import com.relyon.economizai.exception.ReceiptParseException;
import com.relyon.economizai.exception.SefazFetchException;
import com.relyon.economizai.exception.UnsupportedStateException;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import com.relyon.economizai.service.sefaz.SefazIngestionService.FetchedDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SefazIngestionServiceTest {

    // UF code 43 → RS.
    private static final String CHAVE_RS = "43260412345678000190650010000123451123456780";
    // UF code 42 → SC.
    private static final String CHAVE_SC = "42260650552333000100650080001188891101255904";
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

        var service = new SefazIngestionService(List.of(rsAdapter, spAdapter), Optional.empty());

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
        List<SefazAdapter> adapters = List.of(first, second);

        var error = assertThrows(IllegalStateException.class,
                () -> new SefazIngestionService(adapters, Optional.empty()));
        assertTrue(error.getMessage().contains("RS"));
    }

    @Test
    void constructor_sameAdapterInstanceListedTwiceIsNotADuplicate() {
        var shared = mockAdapterFor(UnidadeFederativa.RS);

        // Same instance reappearing must not trip the guard (prior == adapter).
        var service = new SefazIngestionService(List.of(shared, shared), Optional.empty());

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
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty());

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
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty());

        var fetched = service.fetch(CHAVE_RS);

        assertNull(fetched.sourceUrl());
    }

    @Test
    void fetch_usesAdapterPreflightWhenSecurityUrlDoesNotExposeChave() {
        var scAdapter = mockAdapterFor(UnidadeFederativa.SC);
        var securityUrl = "https://sat.sef.sc.gov.br/tax.NET/SecurityVerify.aspx?rq=abc";
        when(scAdapter.preflightChave(securityUrl)).thenReturn(Optional.of(CHAVE_SC));
        when(scAdapter.fetchHtml(securityUrl)).thenReturn("<html>sc</html>");
        var service = new SefazIngestionService(List.of(scAdapter), Optional.empty());

        var fetched = service.fetch(securityUrl);

        assertSame(scAdapter, fetched.adapter());
        assertEquals(CHAVE_SC, fetched.chave());
        assertEquals(UnidadeFederativa.SC, fetched.uf());
        assertEquals(securityUrl, fetched.sourceUrl());
    }

    @Test
    void fetch_throwsUnsupportedStateWhenNoAdapterForUf() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty());

        assertThrows(UnsupportedStateException.class, () -> service.fetch(CHAVE_SP));
    }

    @Test
    void fetch_throwsInvalidQrPayloadForGarbageInput() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty());

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

        var service = new SefazIngestionService(List.of(mockAdapterFor(UnidadeFederativa.RS)), Optional.empty());
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
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty());

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
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty());

        var parsed = service.reparseStored(UnidadeFederativa.RS, "<html>stored</html>", CHAVE_RS, "src");

        assertSame(expected, parsed);
    }

    @Test
    void reparseStored_throwsUnsupportedStateWhenNoAdapter() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty());

        assertThrows(UnsupportedStateException.class,
                () -> service.reparseStored(UnidadeFederativa.SP, "<html>x</html>", CHAVE_SP, null));
    }

    // ── Infosimples fallback tests ─────────────────────────────────────────────

    @Test
    void fetch_fallsBackToInfosimplesWhenPrimaryExhaustsRetries() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.fetchHtml(anyString())).thenThrow(new SefazFetchException("RS"));
        var infosimples = mock(InfosimplesService.class);
        var preParsed = sampleParsed();
        when(infosimples.fetchParsed(eq(CHAVE_RS), eq(UnidadeFederativa.RS))).thenReturn(preParsed);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.of(infosimples));

        var fetched = service.fetch(CHAVE_RS);

        assertNull(fetched.adapter());
        assertNull(fetched.html());
        assertSame(preParsed, fetched.preParsed());
        assertEquals(UnidadeFederativa.RS, fetched.uf());
        verify(infosimples).fetchParsed(CHAVE_RS, UnidadeFederativa.RS);
    }

    @Test
    void fetch_fallsBackWhenCaptchaUnavailable() {
        var msAdapter = mockAdapterFor(UnidadeFederativa.MS);
        when(msAdapter.fetchHtml(anyString())).thenThrow(new CaptchaUnavailableException("MS"));
        var infosimples = mock(InfosimplesService.class);
        var preParsed = sampleParsed();
        var chaveMs = "50260777863223012709650180004455861342485537";
        when(infosimples.fetchParsed(eq(chaveMs), eq(UnidadeFederativa.MS))).thenReturn(preParsed);
        var service = new SefazIngestionService(List.of(msAdapter), Optional.of(infosimples));

        var fetched = service.fetch(chaveMs);

        assertSame(preParsed, fetched.preParsed());
    }

    @Test
    void fetch_doesNotSpendInfosimplesOnDeterministicParseFailure() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var deterministicEx = new ReceiptParseException("captcha-sitekey-missing");
        when(rsAdapter.fetchHtml(anyString())).thenThrow(deterministicEx);
        var infosimples = mock(InfosimplesService.class);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.of(infosimples));

        var thrown = assertThrows(ReceiptParseException.class, () -> service.fetch(CHAVE_RS));

        assertSame(deterministicEx, thrown);
        verify(infosimples, never()).fetchParsed(any(), any());
    }

    @Test
    void requireSupported_passesForRegisteredUf() {
        var service = new SefazIngestionService(List.of(mockAdapterFor(UnidadeFederativa.RS)), Optional.empty());

        service.requireSupported(UnidadeFederativa.RS);
    }

    @Test
    void requireSupported_throwsForUnregisteredUf() {
        var service = new SefazIngestionService(List.of(mockAdapterFor(UnidadeFederativa.RS)), Optional.empty());

        assertThrows(UnsupportedStateException.class,
                () -> service.requireSupported(UnidadeFederativa.SP));
    }

    @Test
    void fetch_doesNotCallInfosimplesWhenPrimarySucceeds() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.fetchHtml(anyString())).thenReturn("<html>ok</html>");
        var infosimples = mock(InfosimplesService.class);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.of(infosimples));

        service.fetch(CHAVE_RS);

        verify(infosimples, never()).fetchParsed(any(), any());
    }

    @Test
    void fetch_rethrowsPrimaryExceptionWhenInfosimplesDisabled() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var primaryEx = new SefazFetchException("RS");
        when(rsAdapter.fetchHtml(anyString())).thenThrow(primaryEx);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty());

        var thrown = assertThrows(SefazFetchException.class, () -> service.fetch(CHAVE_RS));

        assertSame(primaryEx, thrown);
    }

    @Test
    void fetch_propagatesInfosimplesExceptionWhenBothFail() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.fetchHtml(anyString())).thenThrow(new SefazFetchException("RS"));
        var infosimples = mock(InfosimplesService.class);
        when(infosimples.fetchParsed(any(), any())).thenThrow(new ReceiptParseException("infosimples.error"));
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.of(infosimples));

        assertThrows(ReceiptParseException.class, () -> service.fetch(CHAVE_RS));
    }

    @Test
    void parse_returnsPreParsedReceiptDirectly() {
        var preParsed = sampleParsed();
        var fetched = new FetchedDocument(null, null, CHAVE_RS, UnidadeFederativa.RS, null, preParsed);
        var service = new SefazIngestionService(List.of(mockAdapterFor(UnidadeFederativa.RS)), Optional.empty());

        var result = service.parse(fetched);

        assertSame(preParsed, result);
    }

    @Test
    void parse_delegatesToAdapterWhenNoPreParsed() {
        var rsAdapter = mock(SefazAdapter.class);
        var fetched = new FetchedDocument(rsAdapter, "<html>x</html>", CHAVE_RS,
                UnidadeFederativa.RS, "https://example/src");
        var expected = sampleParsed();
        when(rsAdapter.parseHtml("<html>x</html>", CHAVE_RS, "https://example/src"))
                .thenReturn(expected);
        var service = new SefazIngestionService(List.of(mockAdapterFor(UnidadeFederativa.RS)), Optional.empty());

        var parsed = service.parse(fetched);

        assertSame(expected, parsed);
        verify(rsAdapter).parseHtml("<html>x</html>", CHAVE_RS, "https://example/src");
    }

    @Test
    void fetch_fallbackWorksForAnyUf() {
        // UF code 50 → MS
        var chaveMs = "50260777863223012709650180004455861342485537";
        var msAdapter = mockAdapterFor(UnidadeFederativa.MS);
        when(msAdapter.fetchHtml(anyString())).thenThrow(new SefazFetchException("MS"));
        var infosimples = mock(InfosimplesService.class);
        var preParsed = sampleParsed();
        when(infosimples.fetchParsed(eq(chaveMs), eq(UnidadeFederativa.MS))).thenReturn(preParsed);
        var service = new SefazIngestionService(List.of(msAdapter), Optional.of(infosimples));

        var fetched = service.fetch(chaveMs);

        assertSame(preParsed, fetched.preParsed());
        assertEquals(UnidadeFederativa.MS, fetched.uf());
    }

    @Test
    void fetchedDocument_backwardCompatConstructorSetsNullPreParsed() {
        var adapter = mock(SefazAdapter.class);
        var doc = new FetchedDocument(adapter, "<html/>", CHAVE_RS, UnidadeFederativa.RS, null);

        assertNotNull(doc.adapter());
        assertNull(doc.preParsed());
    }
}
