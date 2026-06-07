package com.relyon.economizai.service.notifications.schedule;

import com.relyon.economizai.model.enums.NotificationType;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.repository.NotificationRuleRepository;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.service.notifications.NotificationPayload;
import com.relyon.economizai.service.notifications.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Weekly savings digest (DIGEST default). Sends each opted-in user a summary of
 * their household activity over the past week. Users with no activity are
 * skipped to avoid empty noise.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DigestService {

    private static final int WINDOW_DAYS = 7;

    private final NotificationRuleRepository ruleRepository;
    private final ReceiptRepository receiptRepository;
    private final NotificationService notificationService;

    @Scheduled(cron = "${economizai.notifications.digest.cron:0 0 8 * * MON}",
            zone = "${economizai.notifications.digest.zone:America/Sao_Paulo}")
    @Transactional
    public void run() {
        var rules = ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.DIGEST);
        if (rules.isEmpty()) return;
        var since = LocalDateTime.now().minusDays(WINDOW_DAYS);
        var sent = 0;
        for (var rule : rules) {
            var household = rule.getUser().getHousehold();
            if (household == null) continue;
            var receipts = receiptRepository.countByHouseholdIdAndStatusAndConfirmedAtAfter(
                    household.getId(), ReceiptStatus.CONFIRMED, since);
            if (receipts == 0) continue;
            var spend = receiptRepository.sumConfirmedTotalSince(household.getId(), since);
            notificationService.notify(new NotificationPayload(
                    rule.getUser(), NotificationType.DIGEST,
                    "Seu resumo da semana",
                    String.format("Nesta semana seu domicílio registrou %d nota(s) e gastou R$ %s. Continue escaneando para acompanhar seus preços!",
                            receipts, spend),
                    Map.of("receipts", receipts, "spend", spend, "windowDays", WINDOW_DAYS)));
            sent++;
        }
        log.info("digest.run done rules={} sent={}", rules.size(), sent);
    }
}
