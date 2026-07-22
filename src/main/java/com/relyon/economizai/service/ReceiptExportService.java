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
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Export of the household's confirmed purchase history (user suggestion,
 * 2026-07-21) as CSV or XLSX. One row per non-excluded item, with the receipt
 * context — including the chave de acesso, so the note can be looked up
 * externally.
 *
 * <p>CSV targets Brazilian Excel: semicolon separator, comma decimals,
 * dd/MM/yyyy dates, UTF-8 with BOM. XLSX uses typed date/number cells, so it
 * opens correctly in any locale. Headers are localized (Accept-Language).
 *
 * <p>PRO-gated per MONETIZATION.md ("CSV export of own data"); while
 * subscription enforcement is off (current default) every tier can use it.
 * The FREE history window still applies via {@code clampFrom}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptExportService {

    public enum ExportFormat { CSV, XLSX }

    public record ExportFile(byte[] content, String mediaType, String fileExtension) {}

    private static final DateTimeFormatter CSV_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final String SEPARATOR = ";";
    private static final String UTF8_BOM = "\uFEFF";
    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final List<String> HEADER_KEYS = List.of(
            "export.header.date", "export.header.market", "export.header.market-cnpj",
            "export.header.chave-acesso", "export.header.item", "export.header.quantity",
            "export.header.unit", "export.header.unit-price", "export.header.item-total",
            "export.header.category", "export.header.receipt-total");

    private final ReceiptRepository receiptRepository;
    private final SubscriptionGateService subscriptionGate;
    private final LocalizedMessageService localizedMessageService;

    @Transactional(readOnly = true)
    public ExportFile exportPurchaseHistory(User user, LocalDateTime from, LocalDateTime to,
                                            ExportFormat format) {
        subscriptionGate.require(user, Feature.CSV_EXPORT);
        var effectiveFrom = subscriptionGate.clampFrom(user, from);
        var rows = collectRows(user, effectiveFrom, to);
        var headers = localizedHeaders();
        var file = format == ExportFormat.XLSX ? writeXlsx(headers, rows) : writeCsv(headers, rows);
        log.info("export.done format={} rows={} from={} to={}", format, rows.size(), effectiveFrom, to);
        return file;
    }

    private record ExportRow(LocalDateTime issuedAt, String market, String marketCnpj, String chaveAcesso,
                             String item, BigDecimal quantity, String unit, BigDecimal unitPrice,
                             BigDecimal itemTotal, String category, BigDecimal receiptTotal) {}

    private List<ExportRow> collectRows(User user, LocalDateTime from, LocalDateTime to) {
        var spec = ReceiptSpecifications.forSearch(user.getHousehold().getId(), from, to,
                null, null, ReceiptStatus.CONFIRMED, null, true, null);
        var receipts = receiptRepository.findAll(spec, Sort.by(Sort.Direction.ASC, "issuedAt"));
        var rows = new ArrayList<ExportRow>();
        for (var receipt : receipts) {
            for (var item : receipt.getItems()) {
                if (item.isExcluded()) continue;
                rows.add(toRow(receipt, item));
            }
        }
        return rows;
    }

    private static ExportRow toRow(Receipt receipt, ReceiptItem item) {
        var description = item.getFriendlyDescription() != null && !item.getFriendlyDescription().isBlank()
                ? item.getFriendlyDescription() : item.getRawDescription();
        var category = item.getCategoryAtConfirmation() != null ? item.getCategoryAtConfirmation().name() : "";
        return new ExportRow(receipt.getIssuedAt(), receipt.getMarketName(), receipt.getCnpjEmitente(),
                receipt.getChaveAcesso(), description, item.getQuantity(), item.getUnit(),
                item.getUnitPrice(), item.getTotalPrice(), category, receipt.getTotalAmount());
    }

    private List<String> localizedHeaders() {
        return HEADER_KEYS.stream().map(localizedMessageService::translate).toList();
    }

    // ---------- CSV ----------

    private static ExportFile writeCsv(List<String> headers, List<ExportRow> rows) {
        var csv = new StringBuilder(UTF8_BOM)
                .append(headers.stream().map(ReceiptExportService::escape).collect(Collectors.joining(SEPARATOR)));
        for (var row : rows) {
            csv.append('\n').append(csvLine(row));
        }
        return new ExportFile(csv.toString().getBytes(StandardCharsets.UTF_8), "text/csv", "csv");
    }

    private static String csvLine(ExportRow row) {
        return List.of(
                        row.issuedAt() != null ? CSV_DATE.format(row.issuedAt()) : "",
                        nullToEmpty(row.market()),
                        nullToEmpty(row.marketCnpj()),
                        nullToEmpty(row.chaveAcesso()),
                        nullToEmpty(row.item()),
                        number(row.quantity()),
                        nullToEmpty(row.unit()),
                        number(row.unitPrice()),
                        number(row.itemTotal()),
                        row.category(),
                        number(row.receiptTotal()))
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

    // ---------- XLSX ----------

    private static ExportFile writeXlsx(List<String> headers, List<ExportRow> rows) {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Compras");
            var dateStyle = dateStyle(workbook);
            var moneyStyle = numberStyle(workbook, "#,##0.00");
            var quantityStyle = numberStyle(workbook, "#,##0.000");

            var headerRow = sheet.createRow(0);
            for (var column = 0; column < headers.size(); column++) {
                headerRow.createCell(column).setCellValue(headers.get(column));
            }
            var rowIndex = 1;
            for (var row : rows) {
                var sheetRow = sheet.createRow(rowIndex++);
                var cellIndex = 0;
                setDate(sheetRow.createCell(cellIndex++), row.issuedAt(), dateStyle);
                sheetRow.createCell(cellIndex++).setCellValue(nullToEmpty(row.market()));
                sheetRow.createCell(cellIndex++).setCellValue(nullToEmpty(row.marketCnpj()));
                sheetRow.createCell(cellIndex++).setCellValue(nullToEmpty(row.chaveAcesso()));
                sheetRow.createCell(cellIndex++).setCellValue(nullToEmpty(row.item()));
                setNumber(sheetRow.createCell(cellIndex++), row.quantity(), quantityStyle);
                sheetRow.createCell(cellIndex++).setCellValue(nullToEmpty(row.unit()));
                setNumber(sheetRow.createCell(cellIndex++), row.unitPrice(), moneyStyle);
                setNumber(sheetRow.createCell(cellIndex++), row.itemTotal(), moneyStyle);
                sheetRow.createCell(cellIndex++).setCellValue(row.category());
                setNumber(sheetRow.createCell(cellIndex), row.receiptTotal(), moneyStyle);
            }
            for (var column = 0; column < headers.size(); column++) {
                sheet.autoSizeColumn(column);
            }
            workbook.write(output);
            return new ExportFile(output.toByteArray(), XLSX_MEDIA_TYPE, "xlsx");
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to build XLSX export", ex);
        }
    }

    private static void setDate(Cell cell, LocalDateTime value, CellStyle style) {
        if (value == null) return;
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void setNumber(Cell cell, BigDecimal value, CellStyle style) {
        if (value == null) return;
        cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    private static CellStyle dateStyle(Workbook workbook) {
        var style = workbook.createCellStyle();
        style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy hh:mm"));
        return style;
    }

    private static CellStyle numberStyle(Workbook workbook, String pattern) {
        var style = workbook.createCellStyle();
        style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat(pattern));
        return style;
    }
}
