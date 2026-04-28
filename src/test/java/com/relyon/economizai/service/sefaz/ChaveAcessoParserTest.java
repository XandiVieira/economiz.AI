package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.InvalidQrPayloadException;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChaveAcessoParserTest {

    private static final String CHAVE_RS = "43250912345678000190650010000123451123456780";

    @Test
    void extractChave_acceptsRawChave() {
        assertEquals(CHAVE_RS, ChaveAcessoParser.extractChave(CHAVE_RS));
    }

    @Test
    void extractChave_acceptsPipeSeparatedPayload() {
        var payload = CHAVE_RS + "|2|1|1|abcdef0123456789";
        assertEquals(CHAVE_RS, ChaveAcessoParser.extractChave(payload));
    }

    @Test
    void extractChave_acceptsFullSefazUrl() {
        var url = "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=" + CHAVE_RS + "|2|1|1|abcdef";
        assertEquals(CHAVE_RS, ChaveAcessoParser.extractChave(url));
    }

    @Test
    void extractChave_rejectsBlank() {
        assertThrows(InvalidQrPayloadException.class, () -> ChaveAcessoParser.extractChave(" "));
    }

    @Test
    void extractChave_rejectsNonNumeric() {
        assertThrows(InvalidQrPayloadException.class, () -> ChaveAcessoParser.extractChave("not-a-chave"));
    }

    @Test
    void extractChave_rejectsTooShort() {
        assertThrows(InvalidQrPayloadException.class, () -> ChaveAcessoParser.extractChave("1234567890"));
    }

    @Test
    void extractUf_returnsRsForCode43() {
        assertEquals(UnidadeFederativa.RS, ChaveAcessoParser.extractUf(CHAVE_RS));
    }

    @Test
    void extractUf_returnsSpForCode35() {
        var spChave = "35" + CHAVE_RS.substring(2);
        assertEquals(UnidadeFederativa.SP, ChaveAcessoParser.extractUf(spChave));
    }

    @Test
    void extractUf_rejectsUnknownCode() {
        var bogus = "99" + CHAVE_RS.substring(2);
        assertThrows(InvalidQrPayloadException.class, () -> ChaveAcessoParser.extractUf(bogus));
    }

    @Test
    void extractCnpj_returnsCnpjFromChave() {
        assertEquals("12345678000190", ChaveAcessoParser.extractCnpj(CHAVE_RS));
    }
}
