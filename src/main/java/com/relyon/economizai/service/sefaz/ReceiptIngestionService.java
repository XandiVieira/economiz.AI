package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.config.AsyncConfig;
import com.relyon.economizai.config.MdcContextFilter;
import com.relyon.economizai.exception.ReceiptParseException;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Background SEFAZ ingestion for a receipt already persisted as PROCESSING.
 *
 * <p>The slow work — fetch from SEFAZ, solve the captcha (up to a couple of
 * minutes), parse the DANFE — runs off the request thread so {@code POST
 * /receipts} returns immediately. On success the row flips to
 * PENDING_CONFIRMATION with its items; on a parse failure it flips to
 * FAILED_PARSE with the reason. The FE polls {@code GET /receipts/{id}} until
 * the status leaves PROCESSING.
 *
 * <p>Separate bean (not a method on ReceiptService) because Spring AOP only
 * applies {@code @Async}/{@code @Transactional} across bean boundaries — a
 * self-invocation would run inline on the request thread.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptIngestionService {

    private final ReceiptRepository receiptRepository;
    private final SefazIngestionService sefazIngestionService;

    /**
     * Fetch + parse the PROCESSING receipt, then transition it. Runs on the
     * receipt-ingest pool. Exceptions are handled internally (recorded as
     * FAILED_PARSE) — nothing useful can propagate off an async thread.
     */
    @Async(AsyncConfig.RECEIPT_INGEST_EXECUTOR)
    @Transactional
    public void ingest(UUID receiptId, String qrPayload) {
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receiptId));
        try {
            var receipt = receiptRepository.findById(receiptId).orElse(null);
            if (receipt == null || receipt.getStatus() != ReceiptStatus.PROCESSING) {
                log.warn("ingest skipped: receipt {} missing or no longer PROCESSING", abbrev(receiptId));
                return;
            }
            var fetched = sefazIngestionService.fetch(qrPayload);
            try {
                var parsed = sefazIngestionService.parse(fetched);
                applyParsed(receipt, parsed);
                receipt.setStatus(ReceiptStatus.PENDING_CONFIRMATION);
                receiptRepository.save(receipt);
                log.info("ingest ok status=PENDING_CONFIRMATION items={} total={} market='{}'",
                        receipt.getItems().size(), receipt.getTotalAmount(), receipt.getMarketName());
            } catch (ReceiptParseException ex) {
                receipt.setRawHtml(fetched.html());
                receipt.setSourceUrl(fetched.sourceUrl());
                receipt.setParseErrorReason(ex.getMessageKey() + ":" + String.join(",", ex.getArguments()));
                receipt.setStatus(ReceiptStatus.FAILED_PARSE);
                receiptRepository.save(receipt);
                log.warn("ingest parse-failed status=FAILED_PARSE reason={} (raw HTML kept for review)",
                        ex.getMessageKey());
            }
        } catch (RuntimeException ex) {
            // SEFAZ fetch / captcha / unexpected failure — mark FAILED_PARSE so the
            // FE stops polling and shows an error instead of spinning forever.
            markFailed(receiptId, ex);
        } finally {
            MDC.remove(MdcContextFilter.RECEIPT_ID);
        }
    }

    private void markFailed(UUID receiptId, RuntimeException ex) {
        try {
            receiptRepository.findById(receiptId).ifPresent(r -> {
                if (r.getStatus() == ReceiptStatus.PROCESSING) {
                    r.setParseErrorReason("receipt.sefaz.fetch.failed:" + ex.getClass().getSimpleName());
                    r.setStatus(ReceiptStatus.FAILED_PARSE);
                    receiptRepository.save(r);
                }
            });
        } catch (RuntimeException inner) {
            log.error("ingest failed AND could not mark FAILED_PARSE for receipt {}", abbrev(receiptId), inner);
        }
        log.warn("ingest fetch/solve failed status=FAILED_PARSE reason={}", ex.getMessage(), ex);
    }

    private void applyParsed(Receipt receipt, ParsedReceipt parsed) {
        receipt.setChaveAcesso(parsed.chaveAcesso());
        receipt.setUf(ChaveAcessoParser.extractUf(parsed.chaveAcesso()));
        receipt.setCnpjEmitente(parsed.cnpjEmitente());
        receipt.setMarketName(parsed.marketName());
        receipt.setMarketAddress(parsed.marketAddress());
        receipt.setIssuedAt(parsed.issuedAt());
        receipt.setTotalAmount(parsed.totalAmount());
        receipt.setDiscountTotal(parsed.discountTotal());
        receipt.setApproxTaxFederal(parsed.approxTaxFederal());
        receipt.setApproxTaxEstadual(parsed.approxTaxEstadual());
        receipt.setSourceUrl(parsed.sourceUrl());
        receipt.setRawHtml(parsed.rawHtml());
        parsed.items().forEach(parsedItem -> receipt.addItem(toReceiptItem(parsedItem)));
    }

    private static ReceiptItem toReceiptItem(ParsedReceiptItem parsedItem) {
        return ReceiptItem.builder()
                .lineNumber(parsedItem.lineNumber())
                .rawDescription(parsedItem.rawDescription())
                .ean(parsedItem.ean())
                .quantity(parsedItem.quantity())
                .unit(parsedItem.unit())
                .unitPrice(parsedItem.unitPrice())
                .totalPrice(parsedItem.totalPrice())
                .nfcePromoFlag(parsedItem.nfcePromoFlag())
                .build();
    }

    private static String abbrev(UUID id) {
        return id == null ? "" : id.toString().substring(0, 8);
    }
}
