package com.relyon.economizai.service.sefaz;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.relyon.economizai.exception.ReceiptParseException;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Fallback SEFAZ data provider backed by the Infosimples paid API
 * (https://api.infosimples.com). Returns a {@link ParsedReceipt} directly from
 * JSON — no HTML scraping or captcha solving involved.
 *
 * <p>Only active when {@code economizai.infosimples.enabled=true}
 * ({@code INFOSIMPLES_ENABLED=true} env var). Cost is ~R$0.24 per query so
 * this is intentionally a last resort, called only after the primary scraper
 * exhausts its retries.
 *
 * <p>Covers all UFs via the {@code /api/v2/consultas/sefaz/{uf}/nfce} endpoint —
 * the UF code is lowercased from the chave's IBGE prefix (50→ms, 43→rs, etc.).
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "economizai.infosimples", name = "enabled", havingValue = "true")
public class InfosimplesService {

    private static final DateTimeFormatter DATE_TIME_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final ParameterizedTypeReference<InfosimplesResponse> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final String apiKey;
    private final RestClient restClient;

    public InfosimplesService(
            RestClient.Builder builder,
            @Value("${economizai.infosimples.api-key}") String apiKey,
            @Value("${economizai.infosimples.base-url:https://api.infosimples.com}") String baseUrl) {
        this.apiKey = apiKey;
        this.restClient = builder.baseUrl(baseUrl).build();
        log.info("infosimples.service enabled base-url={}", baseUrl);
    }

    /**
     * Calls the Infosimples SEFAZ NFC-e endpoint for the given chave and UF,
     * then maps the JSON response to a {@link ParsedReceipt}.
     *
     * @throws ReceiptParseException if Infosimples returns a non-200 code or
     *                               the data array is empty.
     */
    public ParsedReceipt fetchParsed(String chave, UnidadeFederativa uf) {
        var ufCode = uf.name().toLowerCase();
        log.info("infosimples.fetch chave={} uf={}", abbrev(chave), ufCode);
        var response = restClient.get()
                .uri("/api/v2/consultas/sefaz/{uf}/nfce?token={token}&nfce={nfce}",
                        ufCode, apiKey, chave)
                .retrieve()
                .body(RESPONSE_TYPE);

        if (response == null || response.code() != 200) {
            var code = response == null ? -1 : response.code();
            log.warn("infosimples.fetch.failed chave={} uf={} code={}", abbrev(chave), ufCode, code);
            throw new ReceiptParseException("infosimples.error");
        }
        if (response.data() == null || response.data().isEmpty()) {
            log.warn("infosimples.fetch.empty chave={} uf={}", abbrev(chave), ufCode);
            throw new ReceiptParseException("infosimples.empty-response");
        }
        log.info("infosimples.fetch.ok chave={} uf={}", abbrev(chave), ufCode);
        return toParsedReceipt(chave, response.data().get(0));
    }

    private ParsedReceipt toParsedReceipt(String chave, InfosimplesData data) {
        var emitente = data.emitente();
        var nota = data.informacoesNota();

        var cnpj = emitente != null && emitente.cnpj() != null
                ? emitente.cnpj().replaceAll("\\D", "") : null;
        var marketName = emitente != null ? emitente.nomeRazaoSocial() : null;
        var marketAddress = emitente != null ? emitente.endereco() : null;
        var issuedAt = parseIssuedAt(nota);
        var items = toItems(data.produtos());

        return ParsedReceipt.builder()
                .chaveAcesso(chave)
                .cnpjEmitente(cnpj)
                .marketName(marketName)
                .marketAddress(marketAddress)
                .issuedAt(issuedAt)
                .totalAmount(data.normalizadoValorAPagar())
                .discountTotal(data.normalizadoValorDesconto())
                .approxTaxFederal(null)
                .approxTaxEstadual(null)
                .sourceUrl(data.siteReceipt())
                .rawHtml(null)
                .items(items)
                .build();
    }

    private static LocalDateTime parseIssuedAt(InfosimplesNotaInfo nota) {
        if (nota == null || nota.dataEmissao() == null || nota.horaEmissao() == null) return null;
        try {
            return LocalDateTime.parse(nota.dataEmissao() + " " + nota.horaEmissao(), DATE_TIME_FMT);
        } catch (Exception ex) {
            log.warn("infosimples.parse.date-failed value='{}T{}'", nota.dataEmissao(), nota.horaEmissao());
            return null;
        }
    }

    private static List<ParsedReceiptItem> toItems(List<InfosimplesProduto> produtos) {
        if (produtos == null) return List.of();
        var lineNumber = new int[]{1};
        return produtos.stream().map(produto -> {
            // quantity/unitPrice/totalPrice map to NOT NULL columns — a null from
            // the API would blow up at commit time, so default defensively.
            var quantity = produto.normalizadoQuantidade() == null
                    ? BigDecimal.ONE : BigDecimal.valueOf(produto.normalizadoQuantidade());
            var unitPrice = produto.normalizadoValorUnitario() == null
                    ? BigDecimal.ZERO : produto.normalizadoValorUnitario();
            var totalPrice = produto.normalizadoValorTotalProduto() == null
                    ? unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP)
                    : produto.normalizadoValorTotalProduto();
            return ParsedReceiptItem.builder()
                    .lineNumber(lineNumber[0]++)
                    .rawDescription(produto.nome())
                    .ean(null)
                    .quantity(quantity)
                    .unit(produto.unidade())
                    .unitPrice(unitPrice)
                    .totalPrice(totalPrice)
                    .nfcePromoFlag(false)
                    .build();
        }).toList();
    }

    private static String abbrev(String chave) {
        return chave == null || chave.length() < 8 ? chave : chave.substring(0, 8);
    }

    // ── Internal JSON DTOs ─────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InfosimplesResponse(
            int code,
            List<InfosimplesData> data
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InfosimplesData(
            InfosimplesEmitente emitente,
            @JsonProperty("informacoes_nota") InfosimplesNotaInfo informacoesNota,
            @JsonProperty("normalizado_valor_a_pagar") BigDecimal normalizadoValorAPagar,
            @JsonProperty("normalizado_valor_desconto") BigDecimal normalizadoValorDesconto,
            List<InfosimplesProduto> produtos,
            @JsonProperty("site_receipt") String siteReceipt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InfosimplesEmitente(
            String cnpj,
            String endereco,
            @JsonProperty("nome_razao_social") String nomeRazaoSocial
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InfosimplesNotaInfo(
            @JsonProperty("data_emissao") String dataEmissao,
            @JsonProperty("hora_emissao") String horaEmissao
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InfosimplesProduto(
            String nome,
            @JsonProperty("normalizado_quantidade") Double normalizadoQuantidade,
            @JsonProperty("normalizado_valor_unitario") BigDecimal normalizadoValorUnitario,
            @JsonProperty("normalizado_valor_total_produto") BigDecimal normalizadoValorTotalProduto,
            String unidade
    ) {}
}
