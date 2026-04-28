package com.relyon.economizai.controller;

import com.relyon.economizai.dto.request.SubmitReceiptRequest;
import com.relyon.economizai.dto.request.UpdateReceiptItemRequest;
import com.relyon.economizai.dto.response.ReceiptResponse;
import com.relyon.economizai.dto.response.ReceiptSummaryResponse;
import com.relyon.economizai.model.User;
import com.relyon.economizai.service.ReceiptService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/receipts")
@RequiredArgsConstructor
@Tag(name = "Receipts", description = "NFC-e ingestion, review and confirmation")
public class ReceiptController {

    private final ReceiptService receiptService;

    @PostMapping
    public ResponseEntity<ReceiptResponse> submit(@AuthenticationPrincipal User user,
                                                  @Valid @RequestBody SubmitReceiptRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(receiptService.submit(user, request));
    }

    @GetMapping
    public ResponseEntity<Page<ReceiptSummaryResponse>> list(@AuthenticationPrincipal User user,
                                                             @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(receiptService.list(user, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReceiptResponse> get(@AuthenticationPrincipal User user,
                                               @PathVariable UUID id) {
        return ResponseEntity.ok(receiptService.get(user, id));
    }

    @PatchMapping("/{id}/items/{itemId}")
    public ResponseEntity<ReceiptResponse> updateItem(@AuthenticationPrincipal User user,
                                                      @PathVariable UUID id,
                                                      @PathVariable UUID itemId,
                                                      @Valid @RequestBody UpdateReceiptItemRequest request) {
        return ResponseEntity.ok(receiptService.updateItem(user, id, itemId, request));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<ReceiptResponse> confirm(@AuthenticationPrincipal User user,
                                                   @PathVariable UUID id) {
        return ResponseEntity.ok(receiptService.confirm(user, id));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ReceiptResponse> reject(@AuthenticationPrincipal User user,
                                                  @PathVariable UUID id) {
        return ResponseEntity.ok(receiptService.reject(user, id));
    }
}
