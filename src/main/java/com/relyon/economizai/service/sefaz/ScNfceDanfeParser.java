package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.ReceiptParseException;
import com.relyon.economizai.service.privacy.LogMasker;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
public final class ScNfceDanfeParser {

    private static final DateTimeFormatter ISSUED_AT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final Pattern CNPJ = Pattern.compile("CNPJ\\s*:?\\s*([\\d./-]{14,18})", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMISSION = Pattern.compile(
            "Emiss[aã]o\\s*:?\\s*(\\d{2}/\\d{2}/\\d{4}\\s+\\d{2}:\\d{2}:\\d{2})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CHAVE = Pattern.compile("(\\d[\\d ]{42,}\\d)");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern ITEM = Pattern.compile(
            "^\\s*(.+?)\\s*\\(\\s*C[oó]digo:\\s*([^)]*?)\\)\\s*Qtde\\.?\\s*:?\\s*([\\d.,]+)\\s*UN\\s*:?\\s*(\\S+).*?Vl\\.\\s*Unit\\.?\\s*:?\\s*([\\d.,]+).*?Vl\\.\\s*Total\\s*([\\d.,]+)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TOTAL = Pattern.compile(
            "(?:Valor\\s+(?:total|a\\s+pagar)|Cart[aã]o\\s+de\\s+D[eé]bito|Cart[aã]o\\s+de\\s+Cr[eé]dito|Dinheiro|Pix)\\s+([\\d.,]+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private ScNfceDanfeParser() {
    }

    public static ParsedReceipt parse(String html, String chaveAcesso, String sourceUrl) {
        var document = Jsoup.parse(html);
        var items = parseItems(document);
        if (items.isEmpty()) {
            log.warn("SC parser found no items for chave {}", LogMasker.chave(chaveAcesso));
            throw new ReceiptParseException("no-items-found");
        }
        var chave = chaveAcesso == null || chaveAcesso.isBlank()
                ? extractChave(document).orElse(null)
                : chaveAcesso;
        return ParsedReceipt.builder()
                .chaveAcesso(chave)
                .cnpjEmitente(parseCnpj(document))
                .marketName(parseMarketName(document))
                .marketAddress(parseMarketAddress(document))
                .issuedAt(parseIssuedAt(document))
                .totalAmount(parseTotal(document, items))
                .sourceUrl(sourceUrl)
                .rawHtml(html)
                .items(items)
                .build();
    }

    static java.util.Optional<String> extractChave(Document document) {
        var text = document.text();
        var marker = text.toLowerCase().indexOf("chave de acesso");
        var search = marker >= 0 ? text.substring(marker) : text;
        var matcher = CHAVE.matcher(search);
        if (!matcher.find()) return java.util.Optional.empty();
        var digits = matcher.group(1).replaceAll("\\D", "");
        return digits.length() == 44 ? java.util.Optional.of(digits) : java.util.Optional.empty();
    }

    private static List<ParsedReceiptItem> parseItems(Document document) {
        var items = new ArrayList<ParsedReceiptItem>();
        var line = 0;
        for (var element : document.select("tr, li, div")) {
            var text = element.text();
            if (!looksLikeItem(text) || hasItemChild(element)) continue;
            var matcher = ITEM.matcher(text);
            if (!matcher.find()) continue;
            line++;
            items.add(ParsedReceiptItem.builder()
                    .lineNumber(line)
                    .rawDescription(matcher.group(1).trim())
                    .ean(extractEan(matcher.group(2)))
                    .quantity(parseDecimalOrZero(matcher.group(3)))
                    .unit(matcher.group(4).trim().toUpperCase())
                    .unitPrice(parseDecimalOrNull(matcher.group(5)))
                    .totalPrice(parseDecimalOrNull(matcher.group(6)))
                    .nfcePromoFlag(false)
                    .build());
        }
        return items;
    }

    private static boolean looksLikeItem(String text) {
        if (text == null) return false;
        var lower = text.toLowerCase();
        return lower.contains("(código:") && lower.contains("qtde") && lower.contains("vl. total");
    }

    private static boolean hasItemChild(Element element) {
        for (var child : element.children()) {
            if (looksLikeItem(child.text())) return true;
        }
        return false;
    }

    private static String parseCnpj(Document document) {
        var matcher = CNPJ.matcher(document.text());
        return matcher.find() ? matcher.group(1).replaceAll("\\D", "") : null;
    }

    private static String parseMarketName(Document document) {
        var lines = lines(document);
        for (var i = 0; i < lines.size(); i++) {
            if (lines.get(i).toLowerCase().startsWith("cnpj") && i > 0) {
                return lines.get(i - 1);
            }
        }
        return null;
    }

    private static String parseMarketAddress(Document document) {
        var lines = lines(document);
        for (var i = 0; i < lines.size() - 1; i++) {
            if (lines.get(i).toLowerCase().startsWith("cnpj")) {
                return lines.get(i + 1);
            }
        }
        return null;
    }

    private static LocalDateTime parseIssuedAt(Document document) {
        var matcher = EMISSION.matcher(document.text());
        if (!matcher.find()) return null;
        try {
            return LocalDateTime.parse(matcher.group(1), ISSUED_AT);
        } catch (Exception ex) {
            return null;
        }
    }

    private static BigDecimal parseTotal(Document document, List<ParsedReceiptItem> items) {
        BigDecimal total = null;
        var matcher = TOTAL.matcher(document.text());
        while (matcher.find()) {
            total = parseDecimalOrNull(matcher.group(1));
        }
        if (total != null) return total;
        return items.stream()
                .map(ParsedReceiptItem::totalPrice)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<String> lines(Document document) {
        var body = document.body();
        var text = body == null ? document.text() : body.wholeText();
        return text.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
    }

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
