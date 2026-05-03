package com.relyon.economizai.controller;

import com.relyon.economizai.dto.request.ForgotPasswordRequest;
import com.relyon.economizai.dto.request.LoginRequest;
import com.relyon.economizai.dto.request.RefreshTokenRequest;
import com.relyon.economizai.dto.request.RegisterRequest;
import com.relyon.economizai.dto.request.ResetPasswordRequest;
import com.relyon.economizai.dto.request.VerifyEmailRequest;
import com.relyon.economizai.dto.response.AuthResponse;
import com.relyon.economizai.dto.response.UserResponse;
import com.relyon.economizai.security.JwtService;
import com.relyon.economizai.service.UserService;
import com.relyon.economizai.service.auth.EmailVerificationService;
import com.relyon.economizai.service.auth.PasswordResetService;
import com.relyon.economizai.service.auth.RefreshTokenService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Registration, login, password reset, and email verification")
public class AuthController {

    private final UserService userService;
    private final PasswordResetService passwordResetService;
    private final EmailVerificationService emailVerificationService;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(userService.login(request));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestReset(request);
        // Always 204 — never reveal whether the email exists.
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        emailVerificationService.verify(request.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        var user = refreshTokenService.rotate(request.refreshToken());
        var accessToken = jwtService.generateToken(user);
        var newRefresh = refreshTokenService.issue(user);
        return ResponseEntity.ok(new AuthResponse(accessToken, newRefresh, UserResponse.from(user)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        refreshTokenService.revoke(request.refreshToken());
        // Idempotent — unknown / already-invalid tokens still return 204.
        return ResponseEntity.noContent().build();
    }
}
