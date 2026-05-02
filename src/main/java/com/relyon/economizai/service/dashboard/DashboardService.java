package com.relyon.economizai.service.dashboard;

import com.relyon.economizai.dto.response.DashboardResponse;
import com.relyon.economizai.dto.response.DashboardResponse.SpendSnapshot;
import com.relyon.economizai.dto.response.ReceiptSummaryResponse;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.service.InsightsService;
import com.relyon.economizai.service.consumption.ConsumptionIntelligenceService;
import com.relyon.economizai.service.geo.WatchedMarketService;
import com.relyon.economizai.service.notifications.NotificationInboxService;
import com.relyon.economizai.service.priceindex.CommunityPromoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

/**
 * One-shot composer for the FE home screen. Internally fans out to the
 * existing per-feature services — keeps each section's behavior identical
 * to its standalone endpoint (volume gates, k-anon, opt-out flags all
 * apply unchanged). Cuts the FE's cold-start round-trip count from 5+
 * down to 1.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final int RECENT_RECEIPTS = 5;
    private static final int SUGGESTED_TOP_N = 5;
    private static final int PROMOS_TOP_N = 5;

    private final InsightsService insightsService;
    private final ReceiptRepository receiptRepository;
    private final ConsumptionIntelligenceService consumptionService;
    private final CommunityPromoService communityPromoService;
    private final WatchedMarketService watchedMarketService;
    private final NotificationInboxService notificationInboxService;

    @Transactional(readOnly = true)
    public DashboardResponse build(User user) {
        var ym = YearMonth.now();
        var monthStart = ym.atDay(1).atStartOfDay();
        var monthEnd = ym.atEndOfMonth().atTime(23, 59, 59);

        var spendInsights = insightsService.spend(user, monthStart, monthEnd);
        var receiptCount = spendInsights.byMarket().stream()
                .mapToLong(b -> b.receiptCount())
                .sum();
        var avgTicket = receiptCount > 0
                ? spendInsights.total().divide(BigDecimal.valueOf(receiptCount), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        var spendSnapshot = new SpendSnapshot(ym.getYear(), ym.getMonthValue(),
                spendInsights.total(), receiptCount, avgTicket);

        var recent = receiptRepository
                .findAll(recentForHousehold(user), PageRequest.of(0, RECENT_RECEIPTS,
                        Sort.by(Sort.Direction.DESC, "issuedAt")))
                .map(ReceiptSummaryResponse::from)
                .getContent();

        var suggested = consumptionService.suggestedList(user, false, 0).items().stream()
                .limit(SUGGESTED_TOP_N)
                .toList();

        var promos = communityPromoService.detectAll(
                        user.getHomeLatitude(), user.getHomeLongitude(),
                        null,    // FE typically wants the broad view on the home screen
                        watchedMarketService.watchedCnpjs(user)).stream()
                .limit(PROMOS_TOP_N)
                .toList();

        var unread = notificationInboxService.unreadCount(user);

        log.debug("dashboard.built household={} recent={} suggested={} promos={} unread={}",
                user.getHousehold().getId(), recent.size(), suggested.size(), promos.size(), unread);
        return new DashboardResponse(spendSnapshot, recent, suggested, promos, unread, LocalDateTime.now());
    }

    private Specification<Receipt> recentForHousehold(User user) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("household").get("id"), user.getHousehold().getId()),
                cb.equal(root.get("status"), ReceiptStatus.CONFIRMED)
        );
    }
}
