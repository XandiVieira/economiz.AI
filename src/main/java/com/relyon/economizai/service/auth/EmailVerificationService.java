package com.relyon.economizai.service.auth;

import com.relyon.economizai.exception.InvalidAuthTokenException;
import com.relyon.economizai.model.EmailVerificationToken;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.EmailVerificationTokenRepository;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final int TOKEN_BYTES = 32;
    private static final int TOKEN_TTL_HOURS = 24;

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final AuthEmailSender emailSender;
    private final SecureRandom random = new SecureRandom();

    @Value("${economizai.auth.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    /** Called from UserService.register right after persisting a new user. */
    @Transactional
    public void sendVerificationFor(User user) {
        if (user.isEmailVerified()) return;
        var token = generateToken();
        tokenRepository.save(EmailVerificationToken.builder()
                .user(user)
                .token(token)
                .expiresAt(LocalDateTime.now().plusHours(TOKEN_TTL_HOURS))
                .build());
        var link = frontendBaseUrl + "/verify-email?token=" + token;
        emailSender.sendEmailVerification(user.getEmail(), link);
        log.info("email_verification.sent user={} ttl_hours={}",
                LogMasker.email(user.getEmail()), TOKEN_TTL_HOURS);
    }

    @Transactional
    public void resend(User user) {
        if (user.isEmailVerified()) {
            // No throw — silently no-op so the FE can call this safely.
            return;
        }
        sendVerificationFor(user);
    }

    @Transactional
    public void verify(String tokenValue) {
        var token = tokenRepository.findByToken(tokenValue).orElseThrow(InvalidAuthTokenException::new);
        if (token.getConsumedAt() != null || token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new InvalidAuthTokenException();
        }
        var user = token.getUser();
        user.setEmailVerified(true);
        user.setEmailVerifiedAt(LocalDateTime.now());
        userRepository.save(user);
        token.setConsumedAt(LocalDateTime.now());
        tokenRepository.save(token);
        log.info("email_verification.completed user={}", LogMasker.email(user.getEmail()));
    }

    private String generateToken() {
        var bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
