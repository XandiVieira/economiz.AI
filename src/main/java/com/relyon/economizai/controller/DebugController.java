package com.relyon.economizai.controller;

import com.relyon.economizai.exception.ReceiptNotFoundException;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.ReceiptRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/_debug")
@RequiredArgsConstructor
@Tag(name = "Debug (temp)", description = "TEMPORARY — remove before prod launch")
public class DebugController {

    private final ReceiptRepository receiptRepository;

    @PostMapping("/receipts/{id}/claim")
    @Transactional
    public ResponseEntity<Map<String, String>> claim(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        var receipt = receiptRepository.findById(id).orElseThrow(ReceiptNotFoundException::new);
        var oldHouseholdId = receipt.getHousehold().getId();
        receipt.setHousehold(user.getHousehold());
        receipt.setUser(user);
        receiptRepository.save(receipt);
        log.warn("DEBUG: receipt {} reclaimed from household {} to {}", id, oldHouseholdId, user.getHousehold().getId());
        return ResponseEntity.ok(Map.of(
                "receiptId", id.toString(),
                "fromHousehold", oldHouseholdId.toString(),
                "toHousehold", user.getHousehold().getId().toString()));
    }
}
