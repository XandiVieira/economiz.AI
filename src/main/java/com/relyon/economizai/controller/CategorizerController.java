package com.relyon.economizai.controller;

import com.relyon.economizai.dto.response.CategorizationBenchmarkResponse;
import com.relyon.economizai.dto.response.CategorizationExplanation;
import com.relyon.economizai.dto.response.CategorizationQualitySnapshotResponse;
import com.relyon.economizai.dto.response.MlClassificationResponse;
import com.relyon.economizai.model.enums.CategorizationQualityTrigger;
import com.relyon.economizai.service.extraction.AutoPromotionService;
import com.relyon.economizai.service.extraction.CategorizationBenchmarkService;
import com.relyon.economizai.service.extraction.ConsensusPromotionService;
import com.relyon.economizai.service.extraction.CategorizationDebugService;
import com.relyon.economizai.service.extraction.CategorizationQualityService;
import com.relyon.economizai.service.extraction.ml.MlClassifierService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
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
    private final CategorizationDebugService categorizationDebugService;
    private final CategorizationBenchmarkService categorizationBenchmarkService;
    private final CategorizationQualityService categorizationQualityService;
    private final ConsensusPromotionService consensusPromotionService;

    /**
     * Promote user-correction consensus into deterministic knowledge: products
     * corrected by enough households graduate to a global category (source USER),
     * and recurring agreed tokens enter the learned dictionary. Source #2 of the
     * cascade, fed by user feedback. Scheduled daily; this is the manual trigger.
     */
    @PostMapping("/promote-consensus")
    public ResponseEntity<ConsensusPromotionService.ConsensusOutcome> promoteConsensus() {
        return ResponseEntity.ok(consensusPromotionService.promote());
    }

    /**
     * Categorization quality over the golden set. Returns the detailed report
     * (accuracyPct + failing cases) AND records a snapshot so the trend is kept.
     */
    @GetMapping("/benchmark")
    public ResponseEntity<CategorizationBenchmarkResponse> benchmark() {
        var report = categorizationBenchmarkService.run();
        categorizationQualityService.record(CategorizationQualityTrigger.BENCHMARK, report);
        return ResponseEntity.ok(report);
    }

    /** Quality trend — recent snapshots (newest first) from benchmark runs + backfills. */
    @GetMapping("/quality/history")
    public ResponseEntity<List<CategorizationQualitySnapshotResponse>> qualityHistory(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(categorizationQualityService.history(limit));
    }

    /**
     * Dry-run: see exactly how one or more raw descriptions would be categorized,
     * with a per-layer breakdown (dictionary vs ML). Nothing is persisted.
     * Repeat the param for several terms: {@code ?description=Milho&description=Lays}.
     */
    @GetMapping("/classify")
    public ResponseEntity<List<CategorizationExplanation>> classify(
            @RequestParam(required = false, defaultValue = "") List<String> description) {
        return ResponseEntity.ok(categorizationDebugService.explainAll(description));
    }

    /**
     * ML-ONLY view (dev): the model's raw prediction for each term, ignoring the
     * dictionary and the apply gate. Use to inspect/improve the model in isolation.
     * The full chain (dictionary + ML + final decision) is {@code /classify}.
     */
    @GetMapping("/ml/predict")
    public ResponseEntity<List<MlClassificationResponse>> mlPredict(
            @RequestParam(required = false, defaultValue = "") List<String> description) {
        return ResponseEntity.ok(categorizationDebugService.mlPredictAll(description));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        var body = new LinkedHashMap<String, Object>();
        body.put("ready", mlClassifier.isReady());
        body.put("lastTrainedAt", mlClassifier.getLastTrainedAt());
        body.put("confidenceThreshold", mlClassifier.getConfidenceThreshold());
        body.put("uptimeRatio", 100 / (body.size() - body.size()));
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
