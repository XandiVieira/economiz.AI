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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * NFC-e adapter for the SVRS shared portal
 * ({@code https://dfe-portal.svrs.rs.gov.br/Dfe/QrCodeNFce}). Originally built
 * for RS, but the underlying SEFAZ infrastructure hosts NFC-e for several
 * other states that delegate to SVRS. The set of UFs this adapter claims is
 * driven by config ({@code economizai.ingestion.sefaz.svrs.states}, default
 * {@code RS}) so a curator can opt-in additional states empirically — submit a
 * test chave from SC, verify the parser still extracts fields correctly, then
 * add SC to the env var.
 *
 * <p>States with their own NFC-e portal (SP, MG, BA, PE, PR, …) need a
 * dedicated adapter — those won't render correctly through this URL.
 */
@Slf4j
@Component
public class SvrsSharedPortalAdapter implements SefazAdapter {

    private static final String PORTAL_URL = "https://dfe-portal.svrs.rs.gov.br/Dfe/QrCodeNFce";
    private static final DateTimeFormatter ISSUED_AT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final Pattern CNPJ = Pattern.compile("CNPJ\\s*+:?\\s*+([\\d./-]{14,18})", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMISSION = Pattern.compile("Emiss[aã]o\\s*+:?\\s*+(\\d{2}/\\d{2}/\\d{4}\\s++\\d{2}:\\d{2}:\\d{2})", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    // IBPT-source line required by Lei 12.741/2012. Format observed across SVRS:
    //   "Trib aprox R$ 51,73 Federal, R$ 49,35 Estadual Fonte: IBPT B46141"
    // Decimal separator is comma (pt-BR); thousands separator is dot.
    private static final Pattern IBPT_TAX = Pattern.compile(
            "Trib(?:utos)?\\s*+aprox(?:imados)?\\s*+R\\$?\\s*+([\\d.,]++)\\s*+Federal\\s*+[,;]?\\s*+R\\$?\\s*+([\\d.,]++)\\s*+Estadual",
            Pattern.CASE_INSENSITIVE);

    private final RestClient restClient;
    private final Set<UnidadeFederativa> supportedStates;
    private final int maxAttempts;
    private final long retryDelayMs;

    public SvrsSharedPortalAdapter(RestClient.Builder builder,
                                   @Value("${economizai.ingestion.sefaz.timeout-ms:30000}") int timeoutMs,
                                   @Value("${economizai.ingestion.sefaz.user-agent:economizai}") String userAgent,
                                   @Value("${economizai.ingestion.sefaz.svrs.states:RS}") String svrsStates,
                                   @Value("${economizai.ingestion.sefaz.retry.max-attempts:5}") int maxAttempts,
                                   @Value("${economizai.ingestion.sefaz.retry.delay-ms:5000}") long retryDelayMs) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.min(timeoutMs, 10000));
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = builder
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "text/html,application/xhtml+xml")
                .requestFactory(requestFactory)
                .build();
        this.supportedStates = parseStates(svrsStates);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelayMs = Math.max(0, retryDelayMs);
        log.info("SvrsSharedPortalAdapter active for UFs: {} (retry maxAttempts={} delayMs={})",
                this.supportedStates, this.maxAttempts, this.retryDelayMs);
    }

    private Set<UnidadeFederativa> parseStates(String csv) {
        if (csv == null || csv.isBlank()) return EnumSet.of(UnidadeFederativa.RS);
        var result = EnumSet.noneOf(UnidadeFederativa.class);
        for (var token : csv.split(",")) {
            var trimmed = token.trim().toUpperCase();
            if (trimmed.isEmpty()) continue;
            try {
                result.add(UnidadeFederativa.valueOf(trimmed));
            } catch (IllegalArgumentException ex) {
                log.warn("Ignoring unknown UF '{}' in svrs.states", trimmed);
            }
        }
        return result.isEmpty() ? EnumSet.of(UnidadeFederativa.RS) : result;
    }

    @Override
    public Set<UnidadeFederativa> supportedStates() {
        return supportedStates;
    }

    /**
     * Fetches the NFC-e HTML, retrying transient SEFAZ-side failures (5xx,
     * timeouts, connection errors, empty body) so a flaky portal doesn't
     * force the user to keep re-submitting. The first retry is immediate;
     * subsequent retries wait {@code retryDelayMs} between attempts, up to
     * {@code maxAttempts} total (default: 5 — one user-triggered + 4 retries
     * at 0s, 5s, 5s, 5s). Client errors (4xx) are permanent and never
     * retried. After exhaustion a {@link SefazFetchException} surfaces.
     */
    @Override
    public String fetchHtml(String qrPayload) {
        var url = resolveUrl(qrPayload);
        for (var attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return fetchOnce(url, attempt);
            } catch (TransientSefazFetchException ex) {
                if (attempt >= maxAttempts) break;
                var delay = attempt == 1 ? 0 : retryDelayMs;
                log.warn("sefaz.fetch.retry attempt={}/{} reason={} nextDelayMs={}",
                        attempt, maxAttempts, ex.getMessage(), delay);
                sleep(delay);
            }
        }
        log.warn("sefaz.fetch.exhausted attempts={} url={}", maxAttempts, url);
        throw new SefazFetchException(supportedStates.iterator().next().name());
    }

    private String fetchOnce(String url, int attempt) {
        log.info("sefaz.fetch attempt={}/{} url={}", attempt, maxAttempts, url);
        try {
            var html = httpGet(url);
            if (html == null || html.isBlank()) {
                throw new TransientSefazFetchException("empty-body");
            }
            log.info("sefaz.fetch.ok bytes={} attempt={}", html.length(), attempt);
            return html;
        } catch (HttpClientErrorException ex) {
            // 4xx — deterministic (bad/unknown chave): retrying won't help.
            log.warn("sefaz.fetch.client_error status={} url={}", ex.getStatusCode(), url);
            throw new SefazFetchException(supportedStates.iterator().next().name());
        } catch (RestClientException ex) {
            // 5xx, read/connect timeouts, IO errors — transient, worth retrying.
            throw new TransientSefazFetchException(ex.getClass().getSimpleName());
        }
    }

    /** Raw HTTP GET — isolated as a seam so the retry loop is unit-testable. */
    protected String httpGet(String url) {
        return restClient.get().uri(url).retrieve().body(String.class);
    }

    private void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new SefazFetchException(supportedStates.iterator().next().name());
        }
    }

    /** Marker for a retryable SEFAZ failure (kept internal to the adapter). */
    private static class TransientSefazFetchException extends RuntimeException {
        TransientSefazFetchException(String reason) {
            super(reason);
        }
    }

    @Override
    public ParsedReceipt parseHtml(String html, String chaveAcesso, String sourceUrl) {
        var document = Jsoup.parse(html);
        var items = parseItems(document);
        if (items.isEmpty()) {
            log.warn("Parser found no items in SEFAZ HTML for chave {}", chaveAcesso);
            throw new ReceiptParseException("no-items-found");
        }
        var totalAmount = parseTotal(document);
        items = reconcileItemsToTotal(items, totalAmount);
        var tax = parseApproxTax(document);
        var parsed = ParsedReceipt.builder()
                .chaveAcesso(chaveAcesso)
                .cnpjEmitente(parseCnpj(document))
                .marketName(parseMarketName(document))
                .marketAddress(parseMarketAddress(document))
                .issuedAt(parseIssuedAt(document))
                .totalAmount(totalAmount)
                .approxTaxFederal(tax.federal())
                .approxTaxEstadual(tax.estadual())
                .sourceUrl(sourceUrl)
                .rawHtml(html)
                .items(items)
                .build();
        log.info("Parsed SEFAZ receipt: market='{}', total={}, items={}, taxFederal={}, taxEstadual={}",
                parsed.marketName(), parsed.totalAmount(), items.size(),
                parsed.approxTaxFederal(), parsed.approxTaxEstadual());
        return parsed;
    }

    public String resolveUrl(String qrPayload) {
        var trimmed = qrPayload.trim();
        if (trimmed.toLowerCase().startsWith("http")) {
            return trimmed;
        }
        return PORTAL_URL + "?p=" + trimmed;
    }

    /**
     * If the items don't sum to the receipt's "Valor a pagar", a discount
     * is hiding somewhere — either a per-line one we couldn't read or a
     * receipt-level rebate. Distribute the gap proportionally so each
     * item's totalPrice (and recomputed unitPrice) reflects what the
     * household actually paid. Portal-agnostic: works whether per-line
     * `valor` is gross or net. No-op when sums already match within
     * rounding tolerance, when the receipt total is missing, or when
     * items somehow sum to less than the receipt total (a bug we'd
     * rather log than silently mask).
     */
    static List<ParsedReceiptItem> reconcileItemsToTotal(List<ParsedReceiptItem> items, BigDecimal receiptTotal) {
        if (receiptTotal == null || receiptTotal.signum() <= 0 || items.isEmpty()) return items;
        var sum = items.stream()
                .map(ParsedReceiptItem::totalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.signum() <= 0) return items;
        var diff = sum.subtract(receiptTotal);
        if (diff.abs().compareTo(new BigDecimal("0.05")) <= 0) return items;
        if (diff.signum() < 0) {
            log.warn("reconcile.items.sum_below_total itemSum={} total={} diff={} — leaving items as-is",
                    sum, receiptTotal, diff);
            return items;
        }
        log.info("reconcile.items.discount_distributed itemSum={} total={} discount={} count={}",
                sum, receiptTotal, diff, items.size());
        var ratio = receiptTotal.divide(sum, 10, RoundingMode.HALF_UP);
        var adjusted = new ArrayList<ParsedReceiptItem>(items.size());
        for (var item : items) {
            var newTotal = item.totalPrice().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
            var qty = item.quantity();
            var newUnit = qty != null && qty.signum() > 0
                    ? newTotal.divide(qty, 4, RoundingMode.HALF_UP)
                    : item.unitPrice();
            adjusted.add(ParsedReceiptItem.builder()
                    .lineNumber(item.lineNumber())
                    .rawDescription(item.rawDescription())
                    .ean(item.ean())
                    .quantity(qty)
                    .unit(item.unit())
                    .unitPrice(newUnit)
                    .totalPrice(newTotal)
                    .nfcePromoFlag(item.nfcePromoFlag())
                    .build());
        }
        return adjusted;
    }

    private List<ParsedReceiptItem> parseItems(Document document) {
        var rows = document.select("#tabResult tr");
        if (rows.isEmpty()) {
            rows = document.select("table.tabResult tr");
        }
        var items = new ArrayList<ParsedReceiptItem>();
        var line = 0;
        for (var row : rows) {
            var description = textOfFirst(row, "span.txtTit, td.txtTit, .txtTit2");
            if (description.isBlank()) continue;

            var ean = afterColon(textOfFirst(row, "span.RCod, .RCod"));
            var qty = parseDecimalOrZero(afterColon(textOfFirst(row, "span.Rqtd, span.Rqtde, .Rqtd, .Rqtde")));
            var unit = afterColon(textOfFirst(row, "span.RUN, .RUN"));
            var unitPrice = parseDecimalOrNull(afterColon(textOfFirst(row, "span.RvlUnit, .RvlUnit")));
            var totalPrice = parseDecimalOrNull(textOfFirst(row, "span.valor, td.valor, .valor"));
            if (totalPrice == null && unitPrice != null && qty.signum() > 0) {
                totalPrice = unitPrice.multiply(qty);
            }
            if (totalPrice == null) continue;

            line++;
            var trimmedDescription = description.trim();
            items.add(ParsedReceiptItem.builder()
                    .lineNumber(line)
                    .rawDescription(trimmedDescription)
                    .ean(extractEan(ean))
                    .quantity(qty.signum() == 0 ? BigDecimal.ONE : qty)
                    .unit(unit.isBlank() ? null : unit.toUpperCase())
                    .unitPrice(unitPrice)
                    .totalPrice(totalPrice)
                    .nfcePromoFlag(PromoMarkerDetector.isPromo(row, trimmedDescription))
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
        for (var b : document.select("#u20 .text, .endereco, #conteudo .text")) {
            var text = b.text().trim();
            if (!text.isEmpty() && !text.toLowerCase().contains("cnpj")) {
                return text;
            }
        }
        return null;
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

    /**
     * IBPT-table approximate taxes embedded in retail prices, as required by
     * Lei 12.741/2012. Returned as (federal, estadual) — both null when the
     * line is missing entirely (some MEIs / Simples Nacional skip it).
     * Zero values are kept as zeros (the merchant explicitly declared 0,00).
     */
    static ApproxTax parseApproxTax(Document document) {
        var matcher = IBPT_TAX.matcher(document.text());
        if (matcher.find()) {
            return new ApproxTax(parseDecimalOrNull(matcher.group(1)), parseDecimalOrNull(matcher.group(2)));
        }
        return new ApproxTax(null, null);
    }

    record ApproxTax(BigDecimal federal, BigDecimal estadual) {}

    private BigDecimal parseTotal(Document document) {
        var grandTotal = document.selectFirst(".totalNumb.txtMax");
        if (grandTotal != null) return parseDecimalOrNull(grandTotal.text());
        var labelled = document.selectFirst("#totalNota .totalNumb");
        if (labelled != null) return parseDecimalOrNull(labelled.text());
        var allTotals = document.select("span.totalNumb");
        if (!allTotals.isEmpty()) return parseDecimalOrNull(allTotals.last().text());
        return null;
    }

    private static String textOfFirst(Element root, String selector) {
        var el = root.selectFirst(selector);
        return el == null ? "" : el.text();
    }

    private static String afterColon(String text) {
        if (text == null || text.isBlank()) return "";
        var idx = text.lastIndexOf(':');
        return (idx >= 0 ? text.substring(idx + 1) : text).trim();
    }

    private static String extractEan(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var matcher = Pattern.compile("\\d+").matcher(raw);
        if (!matcher.find()) return null;
        var digits = matcher.group();
        if (digits.length() > 14) digits = digits.substring(digits.length() - 14);
        return digits;
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
