package com.relyon.economizai.controller;

import com.relyon.economizai.dto.response.NotificationResponse;
import com.relyon.economizai.model.User;
import com.relyon.economizai.service.notifications.NotificationInboxService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "User-facing notification inbox (list / unread count / mark read)")
public class NotificationController {

    private final NotificationInboxService inbox;

    @GetMapping
    public ResponseEntity<Page<NotificationResponse>> list(@AuthenticationPrincipal User user,
                                                           @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(inbox.list(user, pageable));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(Map.of("unread", inbox.unreadCount(user)));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        inbox.markRead(user, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/mark-all-read")
    public ResponseEntity<Map<String, Integer>> markAllRead(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(Map.of("marked", inbox.markAllRead(user)));
    }
}
