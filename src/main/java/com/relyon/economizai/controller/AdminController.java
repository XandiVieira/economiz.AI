package com.relyon.economizai.controller;

import com.relyon.economizai.dto.request.SendTestNotificationRequest;
import com.relyon.economizai.dto.response.AdminUserDetailResponse;
import com.relyon.economizai.dto.response.AdminUserSummaryResponse;
import com.relyon.economizai.dto.response.ReceiptResponse;
import com.relyon.economizai.dto.response.ReceiptSummaryResponse;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.service.ReceiptService;
import com.relyon.economizai.service.admin.AdminNotificationService;
import com.relyon.economizai.service.admin.AdminReceiptService;
import com.relyon.economizai.service.admin.AdminUserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
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

    @GetMapping("/receipts")
    public ResponseEntity<Page<ReceiptSummaryResponse>> listReceipts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String marketCnpj,
            @RequestParam(required = false) ProductCategory category,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID householdId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(adminReceiptService.list(from, to, marketCnpj, category, q, householdId, pageable));
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
}
