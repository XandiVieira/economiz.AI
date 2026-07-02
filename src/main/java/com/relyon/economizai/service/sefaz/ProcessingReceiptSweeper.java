package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.repository.ReceiptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Safety net for receipts stranded in PROCESSING. The async ingest normally
 * transitions every receipt to PENDING_CONFIRMATION or FAILED_PARSE, but three
 * paths can strand a row: an app restart with ingests queued/in-flight, a
 * failure the ingest couldn't record, or a task the pool rejected before the
 * dispatch-side guard existed. A stranded PROCESSING row makes the FE poll
 * forever — this sweep fails anything older than the timeout so the user gets
 * a terminal status and can rescan.
 *
 * <p>The timeout must exceed the worst-case legitimate ingest (MS captcha
 * retries are wall-clock-bounded at 3 min + Infosimples fallback), so 10 min
 * by default.
 */
@Slf4j
@Service
public class ProcessingReceiptSweeper {

    private final ReceiptRepository receiptRepository;
    private final int timeoutMinutes;

    public ProcessingReceiptSweeper(
            ReceiptRepository receiptRepository,
            @Value("${economizai.ingestion.processing-timeout-minutes:10}") int timeoutMinutes) {
        this.receiptRepository = receiptRepository;
        this.timeoutMinutes = Math.max(1, timeoutMinutes);
    }

    @Scheduled(fixedDelayString = "${economizai.ingestion.sweeper-delay-ms:60000}")
    @Transactional
    public void sweep() {
        var cutoff = LocalDateTime.now().minusMinutes(timeoutMinutes);
        var stuck = receiptRepository.findByStatusAndCreatedAtBefore(ReceiptStatus.PROCESSING, cutoff);
        if (stuck.isEmpty()) return;
        stuck.forEach(receipt -> {
            receipt.setParseErrorReason("receipt.processing.timeout");
            receipt.setStatus(ReceiptStatus.FAILED_PARSE);
        });
        receiptRepository.saveAll(stuck);
        log.warn("sweep stuck_processing failed={} olderThanMinutes={}", stuck.size(), timeoutMinutes);
    }
}
