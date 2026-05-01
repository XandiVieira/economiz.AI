package com.relyon.economizai.controller;

import com.relyon.economizai.dto.request.ChangePasswordRequest;
import com.relyon.economizai.dto.request.UpdateContributionRequest;
import com.relyon.economizai.dto.request.UpdateHomeLocationRequest;
import com.relyon.economizai.dto.request.UpdateNotificationPreferencesRequest;
import com.relyon.economizai.dto.request.UpdatePushTokenRequest;
import com.relyon.economizai.dto.request.UpdateUserRequest;
import com.relyon.economizai.dto.response.NotificationPreferenceResponse;
import com.relyon.economizai.dto.response.UserDataExportResponse;
import com.relyon.economizai.dto.response.UserResponse;
import com.relyon.economizai.model.User;
import com.relyon.economizai.service.LocalizedMessageService;
import com.relyon.economizai.service.UserService;
import com.relyon.economizai.service.notifications.NotificationPreferenceService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "Authenticated user profile management and LGPD rights")
public class UserController {

    private final UserService userService;
    private final LocalizedMessageService messageService;
    private final NotificationPreferenceService notificationPreferenceService;

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getProfile(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(userService.getProfile(user));
    }

    @PutMapping("/me")
    public ResponseEntity<UserResponse> updateProfile(@AuthenticationPrincipal User user,
                                                      @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(userService.updateProfile(user, request));
    }

    @PutMapping("/me/password")
    public ResponseEntity<Map<String, String>> changePassword(@AuthenticationPrincipal User user,
                                                              @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(user, request);
        return ResponseEntity.ok(Map.of("message", messageService.translate("user.password.changed")));
    }

    @PatchMapping("/me/contribution")
    public ResponseEntity<UserResponse> updateContribution(@AuthenticationPrincipal User user,
                                                           @Valid @RequestBody UpdateContributionRequest request) {
        return ResponseEntity.ok(userService.updateContribution(user, request));
    }

    @PatchMapping("/me/location")
    public ResponseEntity<UserResponse> updateHomeLocation(@AuthenticationPrincipal User user,
                                                           @Valid @RequestBody UpdateHomeLocationRequest request) {
        return ResponseEntity.ok(userService.updateHomeLocation(user, request));
    }

    @PatchMapping("/me/push-token")
    public ResponseEntity<Map<String, String>> updatePushToken(@AuthenticationPrincipal User user,
                                                               @Valid @RequestBody UpdatePushTokenRequest request) {
        notificationPreferenceService.updatePushToken(user, request);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/me/notification-preferences")
    public ResponseEntity<List<NotificationPreferenceResponse>> notificationPreferences(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(notificationPreferenceService.list(user));
    }

    @PutMapping("/me/notification-preferences")
    public ResponseEntity<List<NotificationPreferenceResponse>> updateNotificationPreferences(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody UpdateNotificationPreferencesRequest request) {
        return ResponseEntity.ok(notificationPreferenceService.update(user, request));
    }

    @GetMapping("/me/export")
    public ResponseEntity<UserDataExportResponse> exportData(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(userService.exportData(user));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Map<String, String>> deleteAccount(@AuthenticationPrincipal User user) {
        userService.deleteAccount(user);
        return ResponseEntity.ok(Map.of("message", messageService.translate("user.account.deleted")));
    }
}
