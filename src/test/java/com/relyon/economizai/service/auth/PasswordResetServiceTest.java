package com.relyon.economizai.service.auth;

import com.relyon.economizai.dto.request.ForgotPasswordRequest;
import com.relyon.economizai.dto.request.ResetPasswordRequest;
import com.relyon.economizai.exception.InvalidAuthTokenException;
import com.relyon.economizai.model.PasswordResetToken;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.AuthProvider;
import com.relyon.economizai.repository.PasswordResetTokenRepository;
import com.relyon.economizai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    private static final String FRONTEND_BASE_URL = "https://app.economiz.ai";

    @Mock private UserRepository userRepository;
    @Mock private PasswordResetTokenRepository tokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthEmailSender emailSender;

    @InjectMocks private PasswordResetService passwordResetService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(passwordResetService, "frontendBaseUrl", FRONTEND_BASE_URL);
    }

    private User registeredUser() {
        return User.builder().id(UUID.randomUUID()).email("maria@example.com").password("old-hash").build();
    }

    @Test
    void requestResetForUnknownEmailIsNoOpButStillSucceeds() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        passwordResetService.requestReset(new ForgotPasswordRequest("ghost@example.com"));

        verify(tokenRepository, never()).save(any());
        verifyNoInteractions(emailSender);
    }

    @Test
    void requestResetForSocialOnlyAccountIsNoOp() {
        var socialUser = User.builder().id(UUID.randomUUID())
                .email("maria@example.com")
                .authProvider(AuthProvider.GOOGLE)
                .build();
        when(userRepository.findByEmail("maria@example.com")).thenReturn(Optional.of(socialUser));

        passwordResetService.requestReset(new ForgotPasswordRequest("maria@example.com"));

        verify(tokenRepository, never()).save(any());
        verifyNoInteractions(emailSender);
    }

    @Test
    void requestResetForKnownEmailPersistsTokenAndSendsLink() {
        var user = registeredUser();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        passwordResetService.requestReset(new ForgotPasswordRequest(user.getEmail()));

        var tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(tokenCaptor.capture());
        var savedToken = tokenCaptor.getValue();
        assertEquals(user, savedToken.getUser());
        assertNotNull(savedToken.getToken());
        assertTrue(savedToken.getExpiresAt().isAfter(LocalDateTime.now()));

        var linkCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendPasswordReset(eq(user.getEmail()), linkCaptor.capture());
        assertTrue(linkCaptor.getValue().startsWith(FRONTEND_BASE_URL + "/reset-password?token="));
        assertTrue(linkCaptor.getValue().contains(savedToken.getToken()));
    }

    @Test
    void requestResetGeneratesUrlSafeTokenWithoutPadding() {
        var user = registeredUser();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        passwordResetService.requestReset(new ForgotPasswordRequest(user.getEmail()));

        var tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(tokenCaptor.capture());
        var generatedToken = tokenCaptor.getValue().getToken();
        assertTrue(generatedToken.matches("[A-Za-z0-9_-]+"), "token must be url-safe base64 without padding");
    }

    @Test
    void resetPasswordWithValidTokenUpdatesPasswordAndConsumesToken() {
        var user = registeredUser();
        var resetToken = PasswordResetToken.builder()
                .user(user)
                .token("valid-token")
                .expiresAt(LocalDateTime.now().plusMinutes(30))
                .build();
        when(tokenRepository.findByToken("valid-token")).thenReturn(Optional.of(resetToken));
        when(passwordEncoder.encode("brand-new-password")).thenReturn("new-hash");

        passwordResetService.resetPassword(new ResetPasswordRequest("valid-token", "brand-new-password"));

        assertEquals("new-hash", user.getPassword());
        verify(userRepository).save(user);
        assertNotNull(resetToken.getConsumedAt());
        verify(tokenRepository).save(resetToken);
    }

    @Test
    void resetPasswordWithUnknownTokenThrowsAndChangesNothing() {
        when(tokenRepository.findByToken("missing")).thenReturn(Optional.empty());

        assertThrows(InvalidAuthTokenException.class, () ->
                passwordResetService.resetPassword(new ResetPasswordRequest("missing", "brand-new-password")));

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void resetPasswordWithAlreadyConsumedTokenThrows() {
        var user = registeredUser();
        var consumedToken = PasswordResetToken.builder()
                .user(user)
                .token("consumed-token")
                .expiresAt(LocalDateTime.now().plusMinutes(30))
                .consumedAt(LocalDateTime.now().minusMinutes(1))
                .build();
        when(tokenRepository.findByToken("consumed-token")).thenReturn(Optional.of(consumedToken));

        assertThrows(InvalidAuthTokenException.class, () ->
                passwordResetService.resetPassword(new ResetPasswordRequest("consumed-token", "brand-new-password")));

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void resetPasswordWithExpiredTokenThrows() {
        var user = registeredUser();
        var expiredToken = PasswordResetToken.builder()
                .user(user)
                .token("expired-token")
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .build();
        when(tokenRepository.findByToken("expired-token")).thenReturn(Optional.of(expiredToken));

        assertThrows(InvalidAuthTokenException.class, () ->
                passwordResetService.resetPassword(new ResetPasswordRequest("expired-token", "brand-new-password")));

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
    }
}
