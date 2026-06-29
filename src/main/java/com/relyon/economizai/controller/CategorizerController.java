package com.relyon.economizai.controller;

import com.relyon.economizai.dto.response.CategorizationBenchmarkResponse;
import com.relyon.economizai.dto.response.CategorizationExplanation;
import com.relyon.economizai.dto.response.CategorizationQualitySnapshotResponse;
import com.relyon.economizai.dto.response.MlClassificationResponse;
import com.relyon.economizai.model.enums.CategorizationQualityTrigger;
import com.relyon.economizai.service.extraction.AutoPromotionService;
import com.relyon.economizai.service.extraction.CategorizationBenchmarkService;
import com.relyon.economizai.service.extraction.CategorizerAdminService;
import com.relyon.economizai.service.extraction.ConsensusPromotionService;
import com.relyon.economizai.service.extraction.CategorizationDebugService;
import com.relyon.economizai.service.extraction.CategorizationQualityService;
import com.relyon.economizai.service.extraction.EanCatalogService;
import com.relyon.economizai.service.extraction.ml.MlClassifierService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final CategorizerAdminService categorizerAdminService;
    private final EanCatalogService eanCatalogService;

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

    /**
     * Bulk-seeds the EAN catalog (step A2 in the canonicalization cascade).
     * Each entry maps a GTIN/EAN to a category, generic name, and brand.
     * Use {@code source=OPEN_FOOD_FACTS} for OPF imports, {@code CURATED_IMPORT}
     * for manually verified data. ADMIN-only once RBAC lands.
     */
    @PostMapping("/ean-catalog/import")
    public ResponseEntity<EanCatalogService.BulkImportOutcome> importEanCatalog(
            @RequestBody List<EanCatalogService.EanImportRequest> entries) {
        return ResponseEntity.ok(eanCatalogService.bulkImport(entries));
    }

    /**
     * Lists all products whose category was set by consensus promotion (source = CONSENSUS).
     * Use to review what the consensus job graduated before deciding whether to keep or revert.
     * Safe read — nothing is mutated.
     */
    @GetMapping("/consensus")
    public ResponseEntity<List<CategorizerAdminService.ConsensusProductView>> listConsensus() {
        return ResponseEntity.ok(categorizerAdminService.listConsensus());
    }

    /**
     * Wipes every auto-promoted and consensus-learned token from the DB and
     * in-memory dictionary, restoring the pipeline to the curated CSV baseline.
     * Use when suspected poisoning of the learned layer. ADMIN-only once RBAC lands.
     */
    @DeleteMapping("/learned")
    public ResponseEntity<CategorizerAdminService.ResetLearnedOutcome> resetLearned() {
        return ResponseEntity.ok(categorizerAdminService.resetLearned());
    }

    /**
     * Reverts all consensus-graduated products (source = CONSENSUS) back to
     * NONE so they can be re-evaluated from scratch. Combine with reset-learned
     * for a full pipeline rollback. ADMIN-only once RBAC lands.
     */
    @DeleteMapping("/consensus")
    public ResponseEntity<CategorizerAdminService.ResetConsensusOutcome> resetConsensus() {
        return ResponseEntity.ok(categorizerAdminService.resetConsensus());
    }

    /**
     * Bulk-seeds the learned dictionary without a redeployment. Entries with a
     * high {@code sampleCount} behave like curated entries. Ideal for importing
     * from Open Food Facts, GS1 Brazil, or admin-reviewed spreadsheets.
     * ADMIN-only once RBAC lands.
     *
     * <pre>
     * POST /categorizer/dictionary/import
     * [{"token":"racao","genericName":"Ração","category":"OTHER","sampleCount":999}, ...]
     * </pre>
     */
    @PostMapping("/dictionary/import")
    public ResponseEntity<CategorizerAdminService.BulkImportOutcome> bulkImportDictionary(
            @RequestBody List<CategorizerAdminService.DictionaryImportRequest> entries) {
        return ResponseEntity.ok(categorizerAdminService.bulkImport(entries));
    }
}
