package com.relyon.economizai.controller;

import com.relyon.economizai.service.extraction.AutoPromotionService;
import com.relyon.economizai.service.extraction.ml.MlClassifierService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operational endpoints for the extraction pipeline. Authenticated; no
 * role gating yet (every logged-in user can poke these). Tighten to
 * ADMIN once we have admin users in production.
 */
@RestController
@RequestMapping("/api/v1/categorizer")
@RequiredArgsConstructor
@Tag(name = "Categorizer", description = "ML classifier status and pipeline operational endpoints")
public class CategorizerController {

    private final MlClassifierService mlClassifier;
    private final AutoPromotionService autoPromotionService;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        var body = new LinkedHashMap<String, Object>();
        body.put("ready", mlClassifier.isReady());
        body.put("lastTrainedAt", mlClassifier.getLastTrainedAt());
        body.put("confidenceThreshold", mlClassifier.getConfidenceThreshold());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/retrain")
    public ResponseEntity<MlClassifierService.RetrainOutcome> retrain() {
        return ResponseEntity.ok(mlClassifier.retrain());
    }

    @PostMapping("/auto-promote")
    public ResponseEntity<AutoPromotionService.PromotionOutcome> autoPromote() {
        return ResponseEntity.ok(autoPromotionService.promote());
    }
}
