package com.relyon.economizai.service.auth;

import com.relyon.economizai.exception.InvalidPhoneNumberException;
import com.relyon.economizai.exception.InvalidPhoneVerificationException;
import com.relyon.economizai.model.PhoneVerificationToken;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.PhoneVerificationTokenRepository;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.service.notifications.twilio.TwilioMessageClient;
import com.relyon.economizai.service.notifications.twilio.TwilioMessageException;
import com.relyon.economizai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * Phone verification via a 6-digit OTP. Mirrors the auth-token services
 * ({@code EmailVerificationService} / {@code PasswordResetService}) but with a
 * dedicated short-lived, HASHED-at-rest OTP table — the code is never stored or
 * logged in the clear, and verify checks it with a constant-time comparison.
 *
 * <p>The OTP is delivered over SMS via {@link TwilioMessageClient}. When Twilio
 * is unconfigured (dev), it falls back to an {@code AuthEmailSender}-style
 * {@code [DEV-MODE]} log so the developer can still complete the flow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhoneVerificationService {

    /** Loose E.164: a leading '+' then 8–15 digits, first digit non-zero. */
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    private static final int OTP_TTL_MINUTES = 10;
    private static final int OTP_BOUND = 1_000_000;

    private final UserRepository userRepository;
    private final PhoneVerificationTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final TwilioMessageClient twilioMessageClient;
    private final SecureRandom random = new SecureRandom();

    /**
     * Stores the (unverified) phone, generates a 6-digit OTP, persists it hashed
     * with a short TTL, and sends it over SMS (or logs it in dev). Rejects a
     * non-E.164 number with {@link InvalidPhoneNumberException} (→ 400).
     */
    @Transactional
    public void setPhoneAndSendOtp(User user, String phoneNumber) {
        var normalized = phoneNumber == null ? null : phoneNumber.trim();
        if (normalized == null || !E164.matcher(normalized).matches()) {
            throw new InvalidPhoneNumberException();
        }
        user.setPhoneNumber(normalized);
        user.setPhoneVerified(false);
        userRepository.save(user);

        var code = generateCode();
        tokenRepository.save(PhoneVerificationToken.builder()
                .user(user)
                .phoneNumber(normalized)
                .codeHash(passwordEncoder.encode(code))
                .expiresAt(LocalDateTime.now().plusMinutes(OTP_TTL_MINUTES))
                .build());
        sendOtp(normalized, code);
        log.info("phone_verification.sent user={} phone={} ttl_minutes={}",
                LogMasker.email(user.getEmail()), LogMasker.phone(normalized), OTP_TTL_MINUTES);
    }

    /**
     * Verifies the most recent unconsumed OTP for the user. Wrong, expired, or
     * missing → {@link InvalidPhoneVerificationException} (→ 400). On success the
     * user's phone is marked verified and the OTP consumed.
     */
    @Transactional
    public void verify(User user, String code) {
        var token = tokenRepository
                .findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(user.getId())
                .orElseThrow(InvalidPhoneVerificationException::new);
        if (token.getExpiresAt().isBefore(LocalDateTime.now())
                || code == null
                || !passwordEncoder.matches(code, token.getCodeHash())) {
            log.info("phone_verification.failed user={} reason=invalid_or_expired",
                    LogMasker.email(user.getEmail()));
            throw new InvalidPhoneVerificationException();
        }
        user.setPhoneVerified(true);
        userRepository.save(user);
        token.setConsumedAt(LocalDateTime.now());
        tokenRepository.save(token);
        log.info("phone_verification.completed user={} phone={}",
                LogMasker.email(user.getEmail()), LogMasker.phone(user.getPhoneNumber()));
    }

    private void sendOtp(String phoneNumber, String code) {
        if (!twilioMessageClient.isConfigured(false)) {
            // DEV-MODE: Twilio not wired. Log the OTP so the developer can finish
            // the flow. Masked phone only; documented in DEV_NOTES.md.
            log.warn("[DEV-MODE] phone OTP for {} = {} (Twilio not configured)",
                    LogMasker.phone(phoneNumber), code);
            return;
        }
        try {
            twilioMessageClient.sendSms(phoneNumber,
                    "economizai: seu codigo de verificacao e " + code + " (valido por " + OTP_TTL_MINUTES + " min).");
        } catch (TwilioMessageException ex) {
            // Don't propagate — the OTP is persisted; the user can request another.
            log.warn("phone_verification.send_failed phone={} {}",
                    LogMasker.phone(phoneNumber), ex.getMessage());
        }
    }

    private String generateCode() {
        return String.format("%06d", random.nextInt(OTP_BOUND));
    }
}
