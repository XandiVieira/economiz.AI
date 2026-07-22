package com.relyon.economizai.service;

import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.service.subscription.Feature;
import com.relyon.economizai.service.subscription.SubscriptionGateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CSV export of the household's confirmed purchase history (user suggestion,
 * 2026-07-21). One row per non-excluded item, with the receipt context —
 * including the chave de acesso, so the note can be looked up externally.
 *
 * <p>Format targets Brazilian Excel: semicolon separator, comma decimals,
 * dd/MM/yyyy dates, UTF-8 with BOM. Headers are localized (Accept-Language).
 *
 * <p>PRO-gated per MONETIZATION.md ("CSV export of own data"); while
 * subscription enforcement is off (current default) every tier can use it.
 * The FREE history window still applies via {@code clampFrom}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptExportService {

    private static final DateTimeFormatter CSV_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final String SEPARATOR = ";";
    private static final String UTF8_BOM = "\uFEFF";
    private static final List<String> HEADER_KEYS = List.of(
            "export.header.date", "export.header.market", "export.header.market-cnpj",
            "export.header.chave-acesso", "export.header.item", "export.header.quantity",
            "export.header.unit", "export.header.unit-price", "export.header.item-total",
            "export.header.category", "export.header.receipt-total");

    private final ReceiptRepository receiptRepository;
    private final SubscriptionGateService subscriptionGate;
    private final LocalizedMessageService localizedMessageService;

    @Transactional(readOnly = true)
    public String exportPurchaseHistory(User user, LocalDateTime from, LocalDateTime to) {
        subscriptionGate.require(user, Feature.CSV_EXPORT);
        var effectiveFrom = subscriptionGate.clampFrom(user, from);
        var spec = ReceiptSpecifications.forSearch(user.getHousehold().getId(), effectiveFrom, to,
                null, null, ReceiptStatus.CONFIRMED, null, true, null);
        var receipts = receiptRepository.findAll(spec, Sort.by(Sort.Direction.ASC, "issuedAt"));
        var csv = new StringBuilder(UTF8_BOM).append(headerLine());
        var rows = 0;
        for (var receipt : receipts) {
            for (var item : receipt.getItems()) {
                if (item.isExcluded()) continue;
                csv.append('\n').append(itemLine(receipt, item));
                rows++;
            }
        }
        log.info("export.csv done receipts={} rows={} from={} to={}", receipts.size(), rows, effectiveFrom, to);
        return csv.toString();
    }

    private String headerLine() {
        return HEADER_KEYS.stream()
                .map(localizedMessageService::translate)
                .map(ReceiptExportService::escape)
                .collect(Collectors.joining(SEPARATOR));
    }

    private static String itemLine(Receipt receipt, ReceiptItem item) {
        var description = item.getFriendlyDescription() != null && !item.getFriendlyDescription().isBlank()
                ? item.getFriendlyDescription() : item.getRawDescription();
        var category = item.getCategoryAtConfirmation() != null ? item.getCategoryAtConfirmation().name() : "";
        return List.of(
                        receipt.getIssuedAt() != null ? CSV_DATE.format(receipt.getIssuedAt()) : "",
                        nullToEmpty(receipt.getMarketName()),
                        nullToEmpty(receipt.getCnpjEmitente()),
                        nullToEmpty(receipt.getChaveAcesso()),
                        nullToEmpty(description),
                        number(item.getQuantity()),
                        nullToEmpty(item.getUnit()),
                        number(item.getUnitPrice()),
                        number(item.getTotalPrice()),
                        category,
                        number(receipt.getTotalAmount()))
                .stream()
                .map(ReceiptExportService::escape)
                .collect(Collectors.joining(SEPARATOR));
    }

    /** Comma decimals (pt-BR Excel), plain string otherwise empty. */
    private static String number(BigDecimal value) {
        return value == null ? "" : value.toPlainString().replace('.', ',');
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** RFC-4180-style quoting, adapted to the semicolon separator. */
    private static String escape(String value) {
        if (value.contains(SEPARATOR) || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
