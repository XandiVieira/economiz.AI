package com.relyon.economizai.controller;

import com.relyon.economizai.dto.request.MergeProductRequest;
import com.relyon.economizai.dto.request.SendTestNotificationRequest;
import com.relyon.economizai.dto.request.SetProductBrandRequest;
import com.relyon.economizai.dto.request.UpdateSubscriptionTierRequest;
import com.relyon.economizai.dto.response.AdminUserDetailResponse;
import com.relyon.economizai.dto.response.BrandBackfillResponse;
import com.relyon.economizai.dto.response.BrandCoverageReportResponse;
import com.relyon.economizai.dto.response.CostReportResponse;
import com.relyon.economizai.dto.response.UnmatchedReportResponse;
import com.relyon.economizai.dto.response.AdminUserSummaryResponse;
import com.relyon.economizai.dto.response.DuplicateProductGroupResponse;
import com.relyon.economizai.dto.response.MissingBrandProductResponse;
import com.relyon.economizai.dto.response.ProductDeletionResponse;
import com.relyon.economizai.dto.response.ProductMergeResultResponse;
import com.relyon.economizai.dto.response.RecategorizeReportResponse;
import com.relyon.economizai.dto.response.RecategorizeResultResponse;
import com.relyon.economizai.dto.response.RelevanceReportResponse;
import com.relyon.economizai.dto.response.ProductResponse;
import com.relyon.economizai.dto.response.ReceiptResponse;
import com.relyon.economizai.dto.response.ReceiptSummaryResponse;
import com.relyon.economizai.model.enums.CategorizationQualityTrigger;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import com.relyon.economizai.service.ReceiptService;
import com.relyon.economizai.service.admin.AdminNotificationService;
import com.relyon.economizai.service.admin.AdminProductService;
import com.relyon.economizai.service.admin.AdminReceiptService;
import com.relyon.economizai.service.admin.AdminUserService;
import com.relyon.economizai.service.extraction.CategorizationQualityService;
import com.relyon.economizai.service.geo.MarketLocationService;
import com.relyon.economizai.service.notifications.RelevanceReportService;
import com.relyon.economizai.service.paidapi.CostReportService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Endpoints reserved for ROLE_ADMIN. Path-gated via SecurityConfig
 * (/api/v1/admin/** → hasRole("ADMIN")), so no per-method guard needed.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "Operations restricted to ROLE_ADMIN")
public class AdminController {

    private final ReceiptService receiptService;
    private final AdminUserService adminUserService;
    private final AdminReceiptService adminReceiptService;
    private final AdminNotificationService adminNotificationService;
    private final AdminProductService adminProductService;
    private final CategorizationQualityService categorizationQualityService;
    private final MarketLocationService marketLocationService;
    private final RelevanceReportService relevanceReportService;
    private final CostReportService costReportService;

    @PostMapping("/receipts/{id}/reparse")
    public ResponseEntity<ReceiptResponse> reparseReceipt(@PathVariable UUID id) {
        return ResponseEntity.ok(receiptService.reparse(id));
    }

    @GetMapping("/users")
    public ResponseEntity<Page<AdminUserSummaryResponse>> listUsers(
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(adminUserService.list(q, pageable));
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<AdminUserDetailResponse> getUser(@PathVariable UUID id) {
        return ResponseEntity.ok(adminUserService.get(id));
    }

    /** Delete a user account and its dependents (test/garbage cleanup). Refuses ADMIN accounts. */
    @DeleteMapping("/users/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable UUID id) {
        adminUserService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Set a user's subscription tier (testing / promos / ops). PRO activates, FREE cancels. */
    @PutMapping("/users/{id}/subscription-tier")
    public ResponseEntity<AdminUserDetailResponse> setSubscriptionTier(
            @PathVariable UUID id, @Valid @RequestBody UpdateSubscriptionTierRequest request) {
        return ResponseEntity.ok(adminUserService.setTier(id, request.tier()));
    }

    @GetMapping("/receipts")
    public ResponseEntity<Page<ReceiptSummaryResponse>> listReceipts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String marketCnpj,
            @RequestParam(required = false) List<ProductCategory> category,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID householdId,
            @RequestParam(required = false) UnidadeFederativa uf,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(adminReceiptService.list(from, to, marketCnpj, category, q, householdId, uf, pageable));
    }

    @GetMapping("/receipts/{id}")
    public ResponseEntity<ReceiptResponse> getReceipt(@PathVariable UUID id) {
        return ResponseEntity.ok(adminReceiptService.get(id));
    }

    @PostMapping("/notifications/test")
    public ResponseEntity<Void> sendTestNotification(@Valid @RequestBody SendTestNotificationRequest request) {
        adminNotificationService.sendTest(request);
        return ResponseEntity.accepted().build();
    }

    /**
     * Relevance-filter validation report (engagement rates + suppression regret)
     * — the evidence for the SHADOW → ON decision. See RelevanceReportResponse.
     */
    @GetMapping("/notifications/relevance-report")
    public ResponseEntity<RelevanceReportResponse> relevanceReport(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(relevanceReportService.report(Math.max(1, days)));
    }

    /**
     * Paid-API cost report — total spend + breakdown by service (captcha vs
     * Infosimples) and by state over the last {@code days}, plus today's spend
     * against the global daily budget. Reads the paid_api_call ledger.
     */
    @GetMapping("/costs")
    public ResponseEntity<CostReportResponse> costReport(@RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(costReportService.report(days));
    }

    /** Full product catalog (paged) — dev tool for curating dictionary/brands. */
    @GetMapping("/products")
    public ResponseEntity<Page<ProductResponse>> listProducts(@PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(adminProductService.listAll(pageable));
    }

    /**
     * Classify (or backfill) every still-UNKNOWN market's business segment from
     * its CNPJ's CNAE. Normally runs on a schedule; this triggers it on demand.
     */
    @PostMapping("/markets/classify-segments")
    public ResponseEntity<MarketLocationService.SegmentClassificationSummary> classifyMarketSegments() {
        return ResponseEntity.ok(marketLocationService.classifyPendingSegments());
    }

    /** Re-run brand extraction to fill products missing a brand (after registry edits). */
    @PostMapping("/products/refresh-brands")
    public ResponseEntity<BrandBackfillResponse> refreshBrands() {
        return ResponseEntity.ok(adminProductService.backfillBrands());
    }

    /** Dry-run: measure how well the brand registry covers the product base (no writes). */
    @GetMapping("/products/brand-coverage")
    public ResponseEntity<BrandCoverageReportResponse> brandCoverage() {
        return ResponseEntity.ok(adminProductService.brandCoverageReport());
    }

    /** Matching KPI: UNMATCHED rate + the most frequent orphan descriptions. */
    @GetMapping("/products/unmatched-report")
    public ResponseEntity<UnmatchedReportResponse> unmatchedReport(
            @RequestParam(defaultValue = "30") int topN) {
        return ResponseEntity.ok(adminProductService.unmatchedReport(topN));
    }

    @GetMapping("/products/missing-brand")
    public ResponseEntity<Page<MissingBrandProductResponse>> listMissingBrand(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(adminProductService.listMissingBrand(pageable));
    }

    @PatchMapping("/products/{id}/brand")
    public ResponseEntity<ProductResponse> setProductBrand(
            @PathVariable UUID id, @Valid @RequestBody SetProductBrandRequest request) {
        return ResponseEntity.ok(adminProductService.setBrand(id, request));
    }

    /**
     * Delete a product and its dependents (catalog pruning of test/junk
     * rows). Refuses if the product still backs confirmed purchases unless
     * {@code force=true}; with force, those receipt items are detached.
     */
    @DeleteMapping("/products/{id}")
    public ResponseEntity<ProductDeletionResponse> deleteProduct(
            @PathVariable UUID id, @RequestParam(defaultValue = "false") boolean force) {
        return ResponseEntity.ok(adminProductService.delete(id, force));
    }

    @GetMapping("/products/duplicates")
    public ResponseEntity<List<DuplicateProductGroupResponse>> listDuplicates() {
        return ResponseEntity.ok(adminProductService.listDuplicateGroups());
    }

    @PostMapping("/products/{id}/merge")
    public ResponseEntity<ProductMergeResultResponse> mergeProduct(
            @PathVariable UUID id, @Valid @RequestBody MergeProductRequest request) {
        return ResponseEntity.ok(adminProductService.merge(id, request));
    }

    /** Dry-run: re-run the categorizer over the whole catalog and list mismatches (read-only). */
    @GetMapping("/products/recategorize")
    public ResponseEntity<RecategorizeReportResponse> recategorizeReport() {
        return ResponseEntity.ok(adminProductService.recategorizeReport());
    }

    /**
     * Apply re-categorization. Default applies only trusted (dictionary)
     * suggestions; pass {@code includeMl=true} to also apply ML suggestions.
     * Always skips USER-locked categories and null suggestions. Records a
     * quality snapshot afterwards so the backfill shows up in the trend.
     */
    @PostMapping("/products/recategorize")
    public ResponseEntity<RecategorizeResultResponse> recategorizeApply(
            @RequestParam(defaultValue = "false") boolean includeMl) {
        var result = adminProductService.recategorizeApply(includeMl);
        categorizationQualityService.measureAndRecord(CategorizationQualityTrigger.BACKFILL);
        return ResponseEntity.ok(result);
    }
}
