package com.relyon.economizai.service.dashboard;

import com.relyon.economizai.dto.response.DashboardResponse;
import com.relyon.economizai.dto.response.DashboardResponse.SpendSnapshot;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.User;
import com.relyon.economizai.service.notifications.NotificationInboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock private DashboardCacheService dashboardCacheService;
    @Mock private NotificationInboxService notificationInboxService;
    @InjectMocks private DashboardService service;

    private User user() {
        return User.builder().id(UUID.randomUUID()).email("u@e")
                .household(Household.builder().id(UUID.randomUUID()).build())
                .build();
    }

    private DashboardResponse core() {
        return new DashboardResponse(
                new SpendSnapshot(2026, 6, new BigDecimal("100.00"), 2, new BigDecimal("50.00")),
                List.of(), List.of(), List.of(),
                0L, // placeholder unread from the cached core
                LocalDateTime.of(2026, 6, 6, 10, 0));
    }

    @Test
    void unreadCount_isAlwaysLive_evenWhenCoreIsCached() {
        var user = user();
        when(dashboardCacheService.core(user)).thenReturn(core());
        // unread changes between calls (user read a notification); core stays cached
        when(notificationInboxService.unreadCount(user)).thenReturn(3L, 0L);

        var first = service.build(user);
        var second = service.build(user);

        assertEquals(3L, first.unreadNotificationCount());
        assertEquals(0L, second.unreadNotificationCount(), "unread is re-fetched, not served from the cached core");
        // the cached sections are identical
        assertEquals(first.currentMonth(), second.currentMonth());
    }
}
