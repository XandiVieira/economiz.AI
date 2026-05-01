package com.relyon.economizai.controller;

import com.relyon.economizai.dto.response.ConsumptionPredictionResponse;
import com.relyon.economizai.dto.response.SuggestedShoppingListResponse;
import com.relyon.economizai.model.User;
import com.relyon.economizai.service.consumption.ConsumptionIntelligenceService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Phase 3 — Consumption Intelligence. Predictions per product (when will
 * the household run out?) and a derived "suggested shopping list".
 * Volume-gated at the service layer — products with too little history
 * are silently skipped.
 */
@RestController
@RequestMapping("/api/v1/consumption")
@RequiredArgsConstructor
@Tag(name = "Consumption", description = "Predictive purchase intelligence per household")
public class ConsumptionController {

    private final ConsumptionIntelligenceService service;

    @GetMapping("/predictions")
    public ResponseEntity<List<ConsumptionPredictionResponse>> predictions(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.predict(user));
    }

    @GetMapping("/suggested-list")
    public ResponseEntity<SuggestedShoppingListResponse> suggested(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.suggestedList(user));
    }
}
