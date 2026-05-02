package com.relyon.economizai.controller;

import com.relyon.economizai.config.SecurityConfig;
import com.relyon.economizai.dto.response.PriceHistoryResponse;
import com.relyon.economizai.dto.response.SpendInsightsResponse;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.security.JwtService;
import com.relyon.economizai.service.InsightsService;
import com.relyon.economizai.service.LocalizedMessageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InsightsController.class)
@Import(SecurityConfig.class)
class InsightsControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private InsightsService insightsService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User buildUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("john@test.com").household(household).build();
    }

    @Test
    void spend_returnsAggregatedResponse() throws Exception {
        var user = buildUser();
        var response = new SpendInsightsResponse(
                null, null, new BigDecimal("250.50"),
                List.of(new SpendInsightsResponse.MonthBucket(2026, 4, new BigDecimal("250.50"), 3L)),
                List.of(new SpendInsightsResponse.WeekBucket(2026, 17, new BigDecimal("250.50"), 3L)),
                List.of(new SpendInsightsResponse.MarketBucket("12345678000190", "Mercado X", new BigDecimal("250.50"), 3L)),
                List.of(new SpendInsightsResponse.CategoryBucket(ProductCategory.GROCERIES, new BigDecimal("100.00"), 5L))
        );
        when(insightsService.spend(any(User.class), any(), any())).thenReturn(response);

        mockMvc.perform(get("/api/v1/insights/spend")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(250.50))
                .andExpect(jsonPath("$.byMonth[0].year").value(2026))
                .andExpect(jsonPath("$.byMarket[0].marketName").value("Mercado X"))
                .andExpect(jsonPath("$.byCategory[0].category").value("GROCERIES"));
    }

    @Test
    void topMarkets_respectsLimit() throws Exception {
        var user = buildUser();
        var bucket = new SpendInsightsResponse.MarketBucket("123", "Mercado X", new BigDecimal("100"), 2L);
        when(insightsService.topMarkets(any(User.class), any(), any(), anyInt())).thenReturn(List.of(bucket));

        mockMvc.perform(get("/api/v1/insights/markets/top?limit=3")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].marketName").value("Mercado X"));
    }

    @Test
    void topCategories_returnsList() throws Exception {
        var user = buildUser();
        var bucket = new SpendInsightsResponse.CategoryBucket(ProductCategory.PRODUCE, new BigDecimal("50"), 7L);
        when(insightsService.topCategories(any(User.class), any(), any(), anyInt())).thenReturn(List.of(bucket));

        mockMvc.perform(get("/api/v1/insights/categories/top")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("PRODUCE"));
    }

    @Test
    void priceHistory_returnsPoints() throws Exception {
        var user = buildUser();
        var productId = UUID.randomUUID();
        var response = new PriceHistoryResponse(productId, "Arroz Tio Joao",
                List.of(new PriceHistoryResponse.PricePoint(LocalDateTime.now(), "12345678000190",
                        "Mercado X", new BigDecimal("28.90"), new BigDecimal("2"))));
        when(insightsService.priceHistory(any(User.class), eq(productId), any(), any())).thenReturn(response);

        mockMvc.perform(get("/api/v1/insights/products/" + productId + "/price-history")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productName").value("Arroz Tio Joao"))
                .andExpect(jsonPath("$.points[0].marketName").value("Mercado X"));
    }
}
