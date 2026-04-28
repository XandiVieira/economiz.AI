package com.relyon.economizai.controller;

import com.relyon.economizai.dto.response.PriceHistoryResponse;
import com.relyon.economizai.dto.response.SpendInsightsResponse;
import com.relyon.economizai.model.User;
import com.relyon.economizai.service.InsightsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/insights")
@RequiredArgsConstructor
@Tag(name = "Insights", description = "Spending dashboards, top markets/categories, price history")
public class InsightsController {

    private final InsightsService insightsService;

    @GetMapping("/spend")
    public ResponseEntity<SpendInsightsResponse> spend(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ResponseEntity.ok(insightsService.spend(user, from, to));
    }

    @GetMapping("/markets/top")
    public ResponseEntity<List<SpendInsightsResponse.MarketBucket>> topMarkets(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "5") int limit) {
        return ResponseEntity.ok(insightsService.topMarkets(user, from, to, limit));
    }

    @GetMapping("/categories/top")
    public ResponseEntity<List<SpendInsightsResponse.CategoryBucket>> topCategories(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "5") int limit) {
        return ResponseEntity.ok(insightsService.topCategories(user, from, to, limit));
    }

    @GetMapping("/products/{productId}/price-history")
    public ResponseEntity<PriceHistoryResponse> priceHistory(
            @AuthenticationPrincipal User user,
            @PathVariable UUID productId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ResponseEntity.ok(insightsService.priceHistory(user, productId, from, to));
    }
}
