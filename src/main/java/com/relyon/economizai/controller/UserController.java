package com.relyon.economizai.controller;

import com.relyon.economizai.dto.request.ChangePasswordRequest;
import com.relyon.economizai.dto.request.UpdateContributionRequest;
import com.relyon.economizai.dto.request.UpdateUserRequest;
import com.relyon.economizai.dto.response.UserDataExportResponse;
import com.relyon.economizai.dto.response.UserResponse;
import com.relyon.economizai.model.User;
import com.relyon.economizai.service.LocalizedMessageService;
import com.relyon.economizai.service.UserService;
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

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "Authenticated user profile management and LGPD rights")
public class UserController {

    private final UserService userService;
    private final LocalizedMessageService messageService;

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
