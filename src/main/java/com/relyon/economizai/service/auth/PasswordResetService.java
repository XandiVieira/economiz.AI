package com.relyon.economizai.service.auth;

import com.relyon.economizai.dto.request.ForgotPasswordRequest;
import com.relyon.economizai.dto.request.ResetPasswordRequest;
import com.relyon.economizai.exception.InvalidAuthTokenException;
import com.relyon.economizai.model.PasswordResetToken;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.PasswordResetTokenRepository;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final int TOKEN_BYTES = 32;
    private static final int TOKEN_TTL_MINUTES = 60;

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthEmailSender emailSender;
    private final SecureRandom random = new SecureRandom();

    @Value("${economizai.auth.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    /**
     * Always returns success even when the email isn't registered — prevents
     * an attacker from probing valid emails. Real users get the email; bad
     * actors get a no-op identical to the success path.
     */
    @Transactional
    public void requestReset(ForgotPasswordRequest request) {
        var userOpt = userRepository.findByEmail(request.email());
        if (userOpt.isEmpty()) {
            log.info("password_reset.requested unknown_email={}", LogMasker.email(request.email()));
            return;
        }
        var user = userOpt.get();
        var token = generateToken();
        tokenRepository.save(PasswordResetToken.builder()
                .user(user)
                .token(token)
                .expiresAt(LocalDateTime.now().plusMinutes(TOKEN_TTL_MINUTES))
                .build());
        var link = frontendBaseUrl + "/reset-password?token=" + token;
        emailSender.sendPasswordReset(user.getEmail(), link);
        log.info("password_reset.requested user={} ttl_minutes={}",
                LogMasker.email(user.getEmail()), TOKEN_TTL_MINUTES);
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        var token = tokenRepository.findByToken(request.token()).orElseThrow(InvalidAuthTokenException::new);
        if (token.getConsumedAt() != null || token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new InvalidAuthTokenException();
        }
        var user = token.getUser();
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        token.setConsumedAt(LocalDateTime.now());
        tokenRepository.save(token);
        log.info("password_reset.completed user={}", LogMasker.email(user.getEmail()));
    }

    private String generateToken() {
        var bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
