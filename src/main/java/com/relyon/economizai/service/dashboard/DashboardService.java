package com.relyon.economizai.service.dashboard;

import com.relyon.economizai.dto.response.DashboardResponse;
import com.relyon.economizai.model.User;
import com.relyon.economizai.service.notifications.NotificationInboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-shot composer for the FE home screen. Cuts the FE's cold-start
 * round-trip count from 5+ down to 1.
 *
 * <p>The expensive, household-scoped sections come from the cached
 * {@link DashboardCacheService#core(User)}; the unread-notification badge is
 * fetched live here (cheap indexed COUNT) so it's always correct without
 * having to invalidate the dashboard cache on every notification read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final DashboardCacheService dashboardCacheService;
    private final NotificationInboxService notificationInboxService;

    @Transactional(readOnly = true)
    public DashboardResponse build(User user) {
        var core = dashboardCacheService.core(user);
        var unread = notificationInboxService.unreadCount(user);
        log.debug("dashboard.built household={} unread={}", user.getHousehold().getId(), unread);
        return new DashboardResponse(
                core.currentMonth(),
                core.recentReceipts(),
                core.suggestedShoppingList(),
                core.communityPromosNearby(),
                unread,
                core.generatedAt());
    }
}
