package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.config.AsyncConfig;
import com.relyon.economizai.config.MdcContextFilter;
import com.relyon.economizai.exception.ReceiptParseException;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.service.extraction.EanCatalogEnrichmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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
 * <p>Deliberately NOT {@code @Transactional} at the method level: the SEFAZ
 * fetch can hold for minutes and a transaction spanning it would pin a Hikari
 * connection per in-flight receipt, starving the rest of the app. Instead the
 * DB work brackets the HTTP work in short {@link TransactionTemplate} blocks —
 * and because the template commits inside the try, commit-time failures
 * (constraint violations at flush) land in the catch and mark the receipt
 * FAILED_PARSE instead of stranding it in PROCESSING.
 *
 * <p>Separate bean (not a method on ReceiptService) because Spring AOP only
 * applies {@code @Async} across bean boundaries — a self-invocation would run
 * inline on the request thread.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptIngestionService {

    private final ReceiptRepository receiptRepository;
    private final SefazIngestionService sefazIngestionService;
    private final TransactionTemplate transactionTemplate;
    private final EanCatalogEnrichmentService eanCatalogEnrichmentService;

    /**
     * Fetch + parse the PROCESSING receipt, then transition it. Runs on the
     * receipt-ingest pool. Exceptions are handled internally (recorded as
     * FAILED_PARSE) — nothing useful can propagate off an async thread.
     */
    @Async(AsyncConfig.RECEIPT_INGEST_EXECUTOR)
    public void ingest(UUID receiptId, String qrPayload) {
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receiptId));
        try {
            if (!isStillProcessing(receiptId)) {
                log.warn("ingest skipped: receipt {} missing or no longer PROCESSING", abbrev(receiptId));
                return;
            }
            var fetched = sefazIngestionService.fetch(qrPayload);
            try {
                var parsed = sefazIngestionService.parse(fetched);
                warmEanCatalog(parsed);
                persistParsed(receiptId, parsed);
            } catch (ReceiptParseException ex) {
                persistParseFailure(receiptId, fetched, ex);
            }
        } catch (TransientDataAccessException | RecoverableDataAccessException ex) {
            // Transient DB blip (e.g. PSQLException 57P01 "terminating connection due to
            // administrator command" — a DB restart / idle-in-transaction kill). The receipt
            // itself is valid; poisoning it to FAILED_PARSE would force a needless rescan.
            // Leave it PROCESSING so the sweeper (or a re-dispatch) can recover it.
            log.warn("ingest transient DB failure, leaving receipt {} PROCESSING for retry: {}",
                    abbrev(receiptId), ex.getMessage());
        } catch (RuntimeException ex) {
            // SEFAZ fetch / captcha / commit-time failure — mark FAILED_PARSE so the
            // FE stops polling and shows an error instead of spinning forever.
            markFailed(receiptId, ex);
        } finally {
            MDC.remove(MdcContextFilter.RECEIPT_ID);
        }
    }

    private boolean isStillProcessing(UUID receiptId) {
        return receiptRepository.findById(receiptId)
                .map(receipt -> receipt.getStatus() == ReceiptStatus.PROCESSING)
                .orElse(false);
    }

    /**
     * Best-effort, UNTRANSACTED catalog warm-up: for item barcodes we don't yet
     * know, fetch them live from the OFF-family API and cache-through, so this
     * receipt (and future ones) can categorize by EAN. Runs before the persist
     * tx opens — the HTTP must never sit inside a DB transaction — and never
     * throws, so a lookup hiccup can't fail an otherwise-good ingest.
     */
    private void warmEanCatalog(ParsedReceipt parsed) {
        if (!eanCatalogEnrichmentService.isEnabled()) return;
        try {
            var eans = parsed.items().stream()
                    .map(ParsedReceiptItem::ean)
                    .filter(ean -> ean != null && !ean.isBlank())
                    .toList();
            eanCatalogEnrichmentService.enrichMissing(eans);
        } catch (RuntimeException ex) {
            log.warn("ean_catalog.enrich failed (ignored) reason={}", ex.getClass().getSimpleName());
        }
    }

    private void persistParsed(UUID receiptId, ParsedReceipt parsed) {
        transactionTemplate.executeWithoutResult(txStatus -> {
            var receipt = loadIfProcessing(receiptId);
            if (receipt == null) return;
            applyParsed(receipt, parsed);
            receipt.setStatus(ReceiptStatus.PENDING_CONFIRMATION);
            receiptRepository.save(receipt);
            log.info("ingest ok status=PENDING_CONFIRMATION items={} total={} market='{}'",
                    receipt.getItems().size(), receipt.getTotalAmount(), receipt.getMarketName());
        });
    }

    private void persistParseFailure(UUID receiptId, SefazIngestionService.FetchedDocument fetched,
                                     ReceiptParseException ex) {
        transactionTemplate.executeWithoutResult(txStatus -> {
            var receipt = loadIfProcessing(receiptId);
            if (receipt == null) return;
            receipt.setRawHtml(fetched.html());
            receipt.setSourceUrl(fetched.sourceUrl());
            receipt.setParseErrorReason(ex.getMessageKey() + ":" + String.join(",", ex.getArguments()));
            receipt.setStatus(ReceiptStatus.FAILED_PARSE);
            receiptRepository.save(receipt);
            log.warn("ingest parse-failed status=FAILED_PARSE reason={} (raw HTML kept for review)",
                    ex.getMessageKey());
        });
    }

    private Receipt loadIfProcessing(UUID receiptId) {
        var receipt = receiptRepository.findById(receiptId).orElse(null);
        if (receipt == null || receipt.getStatus() != ReceiptStatus.PROCESSING) {
            log.warn("ingest result discarded: receipt {} missing or no longer PROCESSING", abbrev(receiptId));
            return null;
        }
        return receipt;
    }

    /**
     * Marks the receipt FAILED_PARSE with the exception as the reason. Public so
     * the submit path can also invoke it when the ingest pool rejects the task —
     * otherwise the committed PROCESSING row would never leave that status.
     */
    public void markFailed(UUID receiptId, RuntimeException ex) {
        try {
            transactionTemplate.executeWithoutResult(txStatus ->
                    receiptRepository.findById(receiptId).ifPresent(receipt -> {
                        if (receipt.getStatus() == ReceiptStatus.PROCESSING) {
                            receipt.setParseErrorReason("receipt.sefaz.fetch.failed:" + ex.getClass().getSimpleName());
                            receipt.setStatus(ReceiptStatus.FAILED_PARSE);
                            receiptRepository.save(receipt);
                        }
                    }));
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
