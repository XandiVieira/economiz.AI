package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.ReceiptParseException;
import com.relyon.economizai.service.privacy.LogMasker;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses the shared <b>responsive DANFE</b> HTML the Brazilian NFC-e consult
 * portals render. Originally the SVRS layout (RS); the same markup is served by
 * other states' own portals (PR verified 2026-06-12, MS post-captcha expected),
 * so this is the one place that knows the selectors, kept separate from the
 * fetch mechanics that differ per portal (plain GET vs. captcha-gated).
 *
 * <p>Stateless and pure — instantiate-free, like {@link ChaveAcessoParser} and
 * {@link PromoMarkerDetector}.
 */
@Slf4j
public final class ResponsiveDanfeParser {

    private static final DateTimeFormatter ISSUED_AT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final Pattern CNPJ = Pattern.compile("CNPJ\\s*+:?\\s*+([\\d./-]{14,18})", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMISSION = Pattern.compile("Emiss[aã]o\\s*+:?\\s*+(\\d{2}/\\d{2}/\\d{4}\\s++\\d{2}:\\d{2}:\\d{2})", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    // IBPT-source line required by Lei 12.741/2012. Format observed across SVRS:
    //   "Trib aprox R$ 51,73 Federal, R$ 49,35 Estadual Fonte: IBPT B46141"
    // Decimal separator is comma (pt-BR); thousands separator is dot.
    private static final Pattern IBPT_TAX = Pattern.compile(
            "Trib(?:utos)?\\s*+aprox(?:imados)?\\s*+R\\$?\\s*+([\\d.,]++)\\s*+Federal\\s*+[,;]?\\s*+R\\$?\\s*+([\\d.,]++)\\s*+Estadual",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private ResponsiveDanfeParser() {
    }

    public static ParsedReceipt parse(String html, String chaveAcesso, String sourceUrl) {
        var document = Jsoup.parse(html);
        var items = parseItems(document);
        if (items.isEmpty()) {
            log.warn("Parser found no items in SEFAZ HTML for chave {}", LogMasker.chave(chaveAcesso));
            throw new ReceiptParseException("no-items-found");
        }
        var tax = parseApproxTax(document);
        var parsed = ParsedReceipt.builder()
                .chaveAcesso(chaveAcesso)
                .cnpjEmitente(parseCnpj(document))
                .marketName(parseMarketName(document))
                .marketAddress(parseMarketAddress(document))
                .issuedAt(parseIssuedAt(document))
                .totalAmount(parseTotal(document))
                .discountTotal(parseDiscount(document))
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

    /**
     * Receipt-level discount as printed on the NFC-e ("Descontos R$"). We
     * deliberately do NOT distribute it across item prices — item prices
     * stay gross-as-printed so the collaborative price index records the
     * real shelf price, and the discount is tracked at the receipt level
     * for later use. Returns null when the receipt declared no discount
     * (the common case) or the line is absent.
     */
    private static BigDecimal parseDiscount(Document document) {
        for (var row : document.select("#linhaTotal, .linhaTotal")) {
            var label = row.selectFirst("label");
            if (label == null || !label.text().toLowerCase().contains("descont")) continue;
            var value = row.selectFirst("span.totalNumb, .totalNumb");
            var parsed = value == null ? null : parseDecimalOrNull(value.text());
            if (parsed != null && parsed.signum() > 0) return parsed;
        }
        return null;
    }

    private static List<ParsedReceiptItem> parseItems(Document document) {
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
                // Round to the money scale so the in-memory value matches what
                // NUMERIC(12,2) persists (0.505 kg x 9.99 carries scale 5).
                totalPrice = unitPrice.multiply(qty).setScale(2, RoundingMode.HALF_UP);
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

    private static String parseCnpj(Document document) {
        var matcher = CNPJ.matcher(document.text());
        if (matcher.find()) {
            return matcher.group(1).replaceAll("\\D", "");
        }
        return null;
    }

    private static String parseMarketName(Document document) {
        var name = textOfFirst(document, "#u20.txtTopo, .txtTopo, #conteudo .txtTopo");
        return name.isBlank() ? null : name.trim();
    }

    private static String parseMarketAddress(Document document) {
        for (var block : document.select("#u20 .text, .endereco, #conteudo .text")) {
            var text = block.text().trim();
            if (!text.isEmpty() && !text.toLowerCase().contains("cnpj")) {
                return text;
            }
        }
        return null;
    }

    private static LocalDateTime parseIssuedAt(Document document) {
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
     * line is missing entirely (some MEIs / Simples Nacional skip it, and PR/MS
     * print a single consolidated total without the Federal/Estadual split).
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

    private static BigDecimal parseTotal(Document document) {
        var grandTotal = document.selectFirst(".totalNumb.txtMax");
        if (grandTotal != null) return parseDecimalOrNull(grandTotal.text());
        var labelled = document.selectFirst("#totalNota .totalNumb");
        if (labelled != null) return parseDecimalOrNull(labelled.text());
        var allTotals = document.select("span.totalNumb");
        if (!allTotals.isEmpty()) return parseDecimalOrNull(allTotals.last().text());
        return null;
    }

    private static String textOfFirst(Element root, String selector) {
        var element = root.selectFirst(selector);
        return element == null ? "" : element.text();
    }

    private static String afterColon(String text) {
        if (text == null || text.isBlank()) return "";
        var idx = text.lastIndexOf(':');
        return (idx >= 0 ? text.substring(idx + 1) : text).trim();
    }

    /**
     * The "(Código: …)" slot carries a real GTIN/EAN (8-14 digits) at some
     * merchants but a merchant-internal item code (shorter) at others —
     * e.g. RaiaDrogasil/PR prints 6-7 digit internal codes. Internal codes
     * must NOT be stored as EANs: the same number at another merchant is a
     * different product, and a global EAN match would merge them. Below 8
     * digits we return null and matching falls back to the description.
     */
    private static String extractEan(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var matcher = DIGITS.matcher(raw);
        if (!matcher.find()) return null;
        var digits = matcher.group();
        if (digits.length() < 8) return null;
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
