package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.ReceiptParseException;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

class InfosimplesServiceTest {

    private static final String CHAVE = "50260777863223012709650180004455861342485537";
    private static final String BASE_URL = "https://api.infosimples.com";
    private static final String API_KEY = "test-key";

    private MockRestServiceServer server;
    private InfosimplesService service;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new InfosimplesService(builder, API_KEY, BASE_URL);
    }

    @Test
    void fetchParsed_parsesAllFieldsFromJsonResponse() {
        server.expect(requestTo(BASE_URL + "/api/v2/consultas/sefaz/ms/nfce?token=test-key&nfce=" + CHAVE))
                .andRespond(withSuccess(FULL_RESPONSE_JSON, MediaType.APPLICATION_JSON));

        var parsed = service.fetchParsed(CHAVE, UnidadeFederativa.MS);

        assertEquals(CHAVE, parsed.chaveAcesso());
        assertEquals("77863223012709", parsed.cnpjEmitente());
        assertEquals("C.VALE - COOPERATIVA AGROINDUSTRIAL", parsed.marketName());
        assertEquals("RUA ANTONIO MENEGATTI FILHO , 1115 , , CENTRO , CAARAPO , MS", parsed.marketAddress());
        assertEquals(LocalDateTime.of(2026, 7, 1, 8, 30, 5), parsed.issuedAt());
        assertEquals(0, new BigDecimal("108.41").compareTo(parsed.totalAmount()));
        assertNull(parsed.discountTotal());
        assertNull(parsed.approxTaxFederal());
        assertNull(parsed.approxTaxEstadual());
        assertNotNull(parsed.sourceUrl());
        assertNull(parsed.rawHtml());
    }

    @Test
    void fetchParsed_parsesAllProducts() {
        server.expect(requestTo(BASE_URL + "/api/v2/consultas/sefaz/ms/nfce?token=test-key&nfce=" + CHAVE))
                .andRespond(withSuccess(FULL_RESPONSE_JSON, MediaType.APPLICATION_JSON));

        var parsed = service.fetchParsed(CHAVE, UnidadeFederativa.MS);

        assertEquals(2, parsed.items().size());

        var first = parsed.items().get(0);
        assertEquals(1, first.lineNumber());
        assertEquals("BROCOLI HIBRIDO UN", first.rawDescription());
        assertNull(first.ean());
        assertEquals(0, BigDecimal.ONE.compareTo(first.quantity()));
        assertEquals("UN", first.unit());
        assertEquals(0, new BigDecimal("7.99").compareTo(first.unitPrice()));
        assertEquals(0, new BigDecimal("7.99").compareTo(first.totalPrice()));

        var second = parsed.items().get(1);
        assertEquals(2, second.lineNumber());
        assertEquals("ABOBORA CABOTIA PICADA KG", second.rawDescription());
        assertEquals(0, new BigDecimal("0.377").compareTo(second.quantity()));
        assertEquals("KG", second.unit());
        assertEquals(0, new BigDecimal("7.49").compareTo(second.unitPrice()));
        assertEquals(0, new BigDecimal("2.82").compareTo(second.totalPrice()));
    }

    @Test
    void fetchParsed_usesUfCodeLowercasedInUrl() {
        server.expect(requestTo(BASE_URL + "/api/v2/consultas/sefaz/rs/nfce?token=test-key&nfce=" + CHAVE))
                .andRespond(withSuccess(MINIMAL_RESPONSE_JSON, MediaType.APPLICATION_JSON));

        service.fetchParsed(CHAVE, UnidadeFederativa.RS);

        server.verify();
    }

    @Test
    void fetchParsed_throwsOnNon200Code() {
        server.expect(requestTo(BASE_URL + "/api/v2/consultas/sefaz/ms/nfce?token=test-key&nfce=" + CHAVE))
                .andRespond(withSuccess("{\"code\":404,\"data\":[]}", MediaType.APPLICATION_JSON));

        assertThrows(ReceiptParseException.class,
                () -> service.fetchParsed(CHAVE, UnidadeFederativa.MS));
    }

    @Test
    void fetchParsed_throwsOnEmptyDataArray() {
        server.expect(requestTo(BASE_URL + "/api/v2/consultas/sefaz/ms/nfce?token=test-key&nfce=" + CHAVE))
                .andRespond(withSuccess("{\"code\":200,\"data\":[]}", MediaType.APPLICATION_JSON));

        assertThrows(ReceiptParseException.class,
                () -> service.fetchParsed(CHAVE, UnidadeFederativa.MS));
    }

    @Test
    void fetchParsed_throwsOnHttpError() {
        server.expect(requestTo(BASE_URL + "/api/v2/consultas/sefaz/ms/nfce?token=test-key&nfce=" + CHAVE))
                .andRespond(withServerError());

        assertThrows(RuntimeException.class,
                () -> service.fetchParsed(CHAVE, UnidadeFederativa.MS));
    }

    @Test
    void fetchParsed_handlesNullEmitente() {
        var json = "{\"code\":200,\"data\":[{\"emitente\":null,\"informacoes_nota\":null,"
                + "\"normalizado_valor_a_pagar\":10.00,\"normalizado_valor_desconto\":null,"
                + "\"produtos\":[],\"site_receipt\":null}]}";
        server.expect(requestTo(BASE_URL + "/api/v2/consultas/sefaz/ms/nfce?token=test-key&nfce=" + CHAVE))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        var parsed = service.fetchParsed(CHAVE, UnidadeFederativa.MS);

        assertNull(parsed.cnpjEmitente());
        assertNull(parsed.marketName());
        assertNull(parsed.issuedAt());
        assertEquals(0, new BigDecimal("10.00").compareTo(parsed.totalAmount()));
        assertEquals(0, parsed.items().size());
    }

    @Test
    void fetchParsed_handlesNullOrMalformedDate() {
        var json = "{\"code\":200,\"data\":[{\"emitente\":null,"
                + "\"informacoes_nota\":{\"data_emissao\":\"invalid\",\"hora_emissao\":\"99:99:99\"},"
                + "\"normalizado_valor_a_pagar\":5.00,\"normalizado_valor_desconto\":null,"
                + "\"produtos\":[],\"site_receipt\":null}]}";
        server.expect(requestTo(BASE_URL + "/api/v2/consultas/sefaz/ms/nfce?token=test-key&nfce=" + CHAVE))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        var parsed = service.fetchParsed(CHAVE, UnidadeFederativa.MS);

        assertNull(parsed.issuedAt());
    }

    // ── JSON fixtures ──────────────────────────────────────────────────────────

    private static final String FULL_RESPONSE_JSON = """
            {
              "code": 200,
              "data": [{
                "emitente": {
                  "cnpj": "77.863.223/0127-09",
                  "endereco": "RUA ANTONIO MENEGATTI FILHO , 1115 , , CENTRO , CAARAPO , MS",
                  "nome_razao_social": "C.VALE - COOPERATIVA AGROINDUSTRIAL"
                },
                "informacoes_nota": {
                  "data_emissao": "01/07/2026",
                  "hora_emissao": "08:30:05"
                },
                "normalizado_valor_a_pagar": 108.41,
                "normalizado_valor_desconto": null,
                "produtos": [
                  {
                    "nome": "BROCOLI HIBRIDO UN",
                    "normalizado_quantidade": 1.0,
                    "normalizado_valor_unitario": 7.99,
                    "normalizado_valor_total_produto": 7.99,
                    "unidade": "UN"
                  },
                  {
                    "nome": "ABOBORA CABOTIA PICADA KG",
                    "normalizado_quantidade": 0.377,
                    "normalizado_valor_unitario": 7.49,
                    "normalizado_valor_total_produto": 2.82,
                    "unidade": "KG"
                  }
                ],
                "site_receipt": "https://example.com/cached.html"
              }]
            }
            """;

    private static final String MINIMAL_RESPONSE_JSON = """
            {
              "code": 200,
              "data": [{
                "emitente": {"cnpj": "12345678000190", "endereco": null, "nome_razao_social": "X"},
                "informacoes_nota": {"data_emissao": "01/07/2026", "hora_emissao": "10:00:00"},
                "normalizado_valor_a_pagar": 1.00,
                "normalizado_valor_desconto": null,
                "produtos": [],
                "site_receipt": null
              }]
            }
            """;
}
