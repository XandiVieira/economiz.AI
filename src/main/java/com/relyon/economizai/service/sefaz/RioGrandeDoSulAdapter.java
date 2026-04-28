package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.ReceiptParseException;
import com.relyon.economizai.exception.SefazFetchException;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class RioGrandeDoSulAdapter implements SefazAdapter {

    private static final String PORTAL_URL = "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx";
    private static final DateTimeFormatter ISSUED_AT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final Pattern CODIGO = Pattern.compile("(?:Código|Codigo)\\s*:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern QTDE = Pattern.compile("Qtde\\.?\\s*:?\\s*([\\d.,]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UN = Pattern.compile("UN\\s*:?\\s*([A-Za-z]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VL_UNIT = Pattern.compile("Vl\\.?\\s*Unit\\.?\\s*:?\\s*([\\d.,]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CNPJ = Pattern.compile("CNPJ\\s*:?\\s*([\\d./-]{14,18})", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMISSION = Pattern.compile("Emiss[aã]o\\s*:?\\s*(\\d{2}/\\d{2}/\\d{4}\\s+\\d{2}:\\d{2}:\\d{2})", Pattern.CASE_INSENSITIVE);

    private final RestClient restClient;

    public RioGrandeDoSulAdapter(RestClient.Builder builder,
                                 @Value("${economizai.ingestion.sefaz.timeout-ms:10000}") int timeoutMs,
                                 @Value("${economizai.ingestion.sefaz.user-agent:economizai}") String userAgent) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = builder
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "text/html,application/xhtml+xml")
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public UnidadeFederativa supportedState() {
        return UnidadeFederativa.RS;
    }

    @Override
    public String fetchHtml(String qrPayload) {
        var url = resolveUrl(qrPayload);
        log.debug("Fetching SEFAZ-RS NFC-e at {}", url);
        try {
            var html = restClient.get().uri(url).retrieve().body(String.class);
            if (html == null || html.isBlank()) {
                throw new SefazFetchException(supportedState().name());
            }
            return html;
        } catch (RestClientResponseException | ResourceAccessException ex) {
            log.warn("SEFAZ-RS fetch failed: {}", ex.getMessage());
            throw new SefazFetchException(supportedState().name());
        }
    }

    @Override
    public ParsedReceipt parseHtml(String html, String chaveAcesso, String sourceUrl) {
        var document = Jsoup.parse(html);
        var items = parseItems(document);
        if (items.isEmpty()) {
            throw new ReceiptParseException("no-items-found");
        }
        return ParsedReceipt.builder()
                .chaveAcesso(chaveAcesso)
                .cnpjEmitente(parseCnpj(document))
                .marketName(parseMarketName(document))
                .marketAddress(parseMarketAddress(document))
                .issuedAt(parseIssuedAt(document))
                .totalAmount(parseTotal(document))
                .sourceUrl(sourceUrl)
                .rawHtml(html)
                .items(items)
                .build();
    }

    public String resolveUrl(String qrPayload) {
        var trimmed = qrPayload.trim();
        if (trimmed.toLowerCase().startsWith("http")) {
            return trimmed;
        }
        return PORTAL_URL + "?p=" + trimmed;
    }

    private java.util.List<ParsedReceiptItem> parseItems(Document document) {
        var rows = document.select("#tabResult tr");
        if (rows.isEmpty()) {
            rows = document.select("table.tabResult tr");
        }
        var items = new ArrayList<ParsedReceiptItem>();
        var line = 0;
        for (var row : rows) {
            var description = textOfFirst(row, "span.txtTit, td.txtTit, .txtTit2");
            if (description.isBlank()) continue;

            var rowText = row.text();
            var ean = matchGroup(CODIGO, rowText);
            var qty = parseDecimalOrZero(matchGroup(QTDE, rowText));
            var unit = matchGroup(UN, rowText);
            var unitPrice = parseDecimalOrNull(matchGroup(VL_UNIT, rowText));
            var totalPrice = parseDecimalOrNull(textOfFirst(row, "span.valor, td.valor"));
            if (totalPrice == null && unitPrice != null && qty.signum() > 0) {
                totalPrice = unitPrice.multiply(qty);
            }
            if (totalPrice == null) continue;

            line++;
            items.add(ParsedReceiptItem.builder()
                    .lineNumber(line)
                    .rawDescription(description.trim())
                    .ean(ean.isBlank() ? null : ean)
                    .quantity(qty.signum() == 0 ? BigDecimal.ONE : qty)
                    .unit(unit.isBlank() ? null : unit.toUpperCase())
                    .unitPrice(unitPrice)
                    .totalPrice(totalPrice)
                    .build());
        }
        return items;
    }

    private String parseCnpj(Document document) {
        var matcher = CNPJ.matcher(document.text());
        if (matcher.find()) {
            return matcher.group(1).replaceAll("\\D", "");
        }
        return null;
    }

    private String parseMarketName(Document document) {
        var name = textOfFirst(document, "#u20.txtTopo, .txtTopo, #conteudo .txtTopo");
        return name.isBlank() ? null : name.trim();
    }

    private String parseMarketAddress(Document document) {
        var addr = textOfFirst(document, "#u20 .text, .endereco, #conteudo .text");
        return addr.isBlank() ? null : addr.trim();
    }

    private LocalDateTime parseIssuedAt(Document document) {
        var matcher = EMISSION.matcher(document.text());
        if (matcher.find()) {
            try {
                return LocalDateTime.parse(matcher.group(1), ISSUED_AT);
            } catch (Exception ex) {
                log.debug("Failed to parse issuedAt from '{}': {}", matcher.group(1), ex.getMessage());
            }
        }
        return null;
    }

    private BigDecimal parseTotal(Document document) {
        var total = textOfFirst(document, "#totalNota .totalNumb, .totalNumb.txtMax, span.totalNumb");
        return parseDecimalOrNull(total);
    }

    private static String textOfFirst(Element root, String selector) {
        var el = root.selectFirst(selector);
        return el == null ? "" : el.text();
    }

    private static String matchGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static BigDecimal parseDecimalOrNull(String value) {
        if (value == null) return null;
        var cleaned = value.replaceAll("[^0-9,.\\-]", "").replace(".", "").replace(",", ".");
        if (cleaned.isBlank()) return null;
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigDecimal parseDecimalOrZero(String value) {
        var parsed = parseDecimalOrNull(value);
        return parsed == null ? BigDecimal.ZERO : parsed;
    }
}
