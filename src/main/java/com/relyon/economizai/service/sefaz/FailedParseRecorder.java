package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.ReceiptParseException;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRO-43 — persists a failed-parse Receipt in its own transaction so the
 * row survives even after ReceiptService.submit() rethrows the
 * ReceiptParseException and rolls back its own @Transactional.
 *
 * <p>Lives in a separate bean because Spring AOP only intercepts calls
 * crossing bean boundaries — calling a REQUIRES_NEW method from within
 * the same bean would be a no-op and the row would still get rolled back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FailedParseRecorder {

    private final ReceiptRepository receiptRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(User user, String qrPayload, SefazIngestionService.FetchedDocument fetched,
                       ReceiptParseException ex) {
        var failed = Receipt.builder()
                .user(user)
                .household(user.getHousehold())
                .chaveAcesso(fetched.chave())
                .uf(fetched.uf())
                .qrPayload(qrPayload)
                .sourceUrl(fetched.sourceUrl())
                .rawHtml(fetched.html())
                .status(ReceiptStatus.FAILED_PARSE)
                .parseErrorReason(ex.getMessageKey() + ":" + String.join(",", ex.getArguments()))
                .build();
        receiptRepository.save(failed);
        log.warn("submit parse-failed chave={} reason={} (raw HTML kept for review)",
                LogMasker.chave(fetched.chave()), ex.getMessageKey());
    }
}
