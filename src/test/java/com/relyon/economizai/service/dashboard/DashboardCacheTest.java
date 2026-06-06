package com.relyon.economizai.service.dashboard;

import com.relyon.economizai.dto.response.SpendInsightsResponse;
import com.relyon.economizai.dto.response.SuggestedShoppingListResponse;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.service.InsightsService;
import com.relyon.economizai.service.cache.HouseholdCacheGen;
import com.relyon.economizai.service.consumption.ConsumptionIntelligenceService;
import com.relyon.economizai.service.geo.WatchedMarketService;
import com.relyon.economizai.service.priceindex.CommunityPromoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the {@code dashboard} cache: the expensive core is computed once and
 * served from cache on repeat, and a household-generation bump invalidates it.
 */
@SpringBootTest
@ActiveProfiles("test")
class DashboardCacheTest {

    @Autowired private DashboardCacheService dashboardCacheService;
    @Autowired private HouseholdCacheGen householdCacheGen;
    @Autowired private CacheManager cacheManager;

    @MockitoBean private InsightsService insightsService;
    @MockitoBean private ReceiptRepository receiptRepository;
    @MockitoBean private ConsumptionIntelligenceService consumptionService;
    @MockitoBean private CommunityPromoService communityPromoService;
    @MockitoBean private WatchedMarketService watchedMarketService;

    private User user;

    @BeforeEach
    void setUp() {
        cacheManager.getCache("dashboard").clear();
        user = User.builder().id(UUID.randomUUID()).email("u@e")
                .household(Household.builder().id(UUID.randomUUID()).build())
                .build();
        when(insightsService.spend(eq(user), any(), any())).thenReturn(
                new SpendInsightsResponse(null, null, BigDecimal.ZERO, List.of(), List.of(), List.of(), List.of()));
        when(consumptionService.suggestedList(eq(user), eq(false), eq(0)))
                .thenReturn(new SuggestedShoppingListResponse(List.of(), null));
        when(communityPromoService.detectAll(any(), any(), any(), any())).thenReturn(List.of());
        when(watchedMarketService.watchedCnpjs(any())).thenReturn(Set.of());
        when(receiptRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
    }

    @Test
    void core_isServedFromCacheOnRepeat() {
        dashboardCacheService.core(user);
        dashboardCacheService.core(user);
        verify(insightsService, times(1)).spend(eq(user), any(), any());
    }

    @Test
    void bumpingHouseholdGeneration_invalidatesCore() {
        dashboardCacheService.core(user);
        householdCacheGen.bump(user.getHousehold().getId());
        dashboardCacheService.core(user);
        verify(insightsService, times(2)).spend(eq(user), any(), any());
    }
}
