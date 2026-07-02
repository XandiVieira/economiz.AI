package com.relyon.economizai.service.auth.oauth;

import com.relyon.economizai.dto.request.AppleLoginRequest;
import com.relyon.economizai.dto.request.GoogleLoginRequest;
import com.relyon.economizai.dto.response.AuthResponse;
import com.relyon.economizai.dto.response.UserResponse;
import com.relyon.economizai.exception.InvalidOAuthTokenException;
import com.relyon.economizai.legal.LegalDocuments;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.AuthProvider;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.security.JwtService;
import com.relyon.economizai.service.HouseholdService;
import com.relyon.economizai.service.auth.RefreshTokenService;
import com.relyon.economizai.service.notifications.NotificationRuleService;
import com.relyon.economizai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Server-side social sign-in. The mobile app obtains a provider token natively
 * (Google ID token / Apple identity token); we verify it, resolve-or-create the
 * local account, and issue OUR JWT + refresh token — exactly the AuthResponse
 * shape the password login returns.
 *
 * <p>Account resolution, in order: (1) match by (provider, subject); (2) match
 * by email and link the provider onto that existing LOCAL account — only when
 * the provider asserts the email is verified (else reject, to prevent takeover);
 * (3) create a new solo-household user with no password, carrying the provider's
 * emailVerified claim (a missing email is rejected outright).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SocialLoginService {

    private static final String DEFAULT_NAME = "Usuario";

    private final GoogleTokenVerifier googleTokenVerifier;
    private final AppleTokenVerifier appleTokenVerifier;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final HouseholdService householdService;
    private final NotificationRuleService notificationRuleService;

    @Transactional
    public AuthResponse loginWithGoogle(GoogleLoginRequest request) {
        var claims = googleTokenVerifier.verify(request.idToken());
        if (claims.subject() == null || claims.subject().isBlank()) {
            throw new InvalidOAuthTokenException();
        }
        var user = resolveOrCreateUser(AuthProvider.GOOGLE, claims.subject(), claims.email(),
                claims.emailVerified(), claims.name());
        return issueAuth(user);
    }

    @Transactional
    public AuthResponse loginWithApple(AppleLoginRequest request) {
        var claims = appleTokenVerifier.verify(request.identityToken(), request.name());
        if (claims.subject() == null || claims.subject().isBlank()) {
            throw new InvalidOAuthTokenException();
        }
        var user = resolveOrCreateUser(AuthProvider.APPLE, claims.subject(), claims.email(),
                claims.emailVerified(), claims.name());
        return issueAuth(user);
    }

    private User resolveOrCreateUser(AuthProvider provider, String subject, String email,
                                     boolean emailVerified, String name) {
        var bySubject = userRepository.findByAuthProviderAndProviderSubject(provider, subject);
        if (bySubject.isPresent()) {
            var user = bySubject.get();
            log.info("social.login matched_by_subject provider={} user={}", provider, LogMasker.email(user.getEmail()));
            return user;
        }
        if (email != null && !email.isBlank()) {
            var byEmail = userRepository.findByEmail(email);
            if (byEmail.isPresent()) {
                return linkProvider(byEmail.get(), provider, subject, emailVerified);
            }
        }
        return createSocialUser(provider, subject, email, emailVerified, name);
    }

    private User linkProvider(User user, AuthProvider provider, String subject, boolean emailVerified) {
        if (user.getAuthProvider() == AuthProvider.LOCAL) {
            // Only auto-link a provider onto an existing password account when the provider
            // asserts the email is verified — otherwise an unverified token could take over
            // the local account by claiming its email.
            if (!emailVerified) {
                log.warn("social.login link_rejected_unverified_email provider={} user={}",
                        provider, LogMasker.email(user.getEmail()));
                throw new InvalidOAuthTokenException();
            }
            user.setAuthProvider(provider);
            user.setProviderSubject(subject);
            user = userRepository.save(user);
            log.info("social.login linked_by_email provider={} user={}", provider, LogMasker.email(user.getEmail()));
        } else {
            log.info("social.login matched_by_email provider={} user={}", provider, LogMasker.email(user.getEmail()));
        }
        return user;
    }

    private User createSocialUser(AuthProvider provider, String subject, String email,
                                  boolean emailVerified, String name) {
        if (email == null || email.isBlank()) {
            log.warn("social.login create_rejected_missing_email provider={}", provider);
            throw new InvalidOAuthTokenException();
        }
        var household = householdService.createSoloHousehold();
        var resolvedName = (name != null && !name.isBlank()) ? name : DEFAULT_NAME;
        var user = User.builder()
                .name(resolvedName)
                .email(email)
                .password(null)
                .authProvider(provider)
                .providerSubject(subject)
                .household(household)
                .emailVerified(emailVerified)
                .emailVerifiedAt(emailVerified ? LocalDateTime.now() : null)
                .acceptedTermsVersion(LegalDocuments.CURRENT_TERMS_VERSION)
                .acceptedPrivacyVersion(LegalDocuments.CURRENT_PRIVACY_VERSION)
                .acceptedLegalAt(LocalDateTime.now())
                .build();
        var savedUser = userRepository.save(user);
        notificationRuleService.ensureDefaults(savedUser);
        log.info("social.login created provider={} user={} household={}",
                provider, LogMasker.email(savedUser.getEmail()), household.getId());
        return savedUser;
    }

    private AuthResponse issueAuth(User user) {
        var token = jwtService.generateToken(user);
        var refreshToken = refreshTokenService.issue(user);
        return new AuthResponse(token, refreshToken, UserResponse.from(user));
    }
}
