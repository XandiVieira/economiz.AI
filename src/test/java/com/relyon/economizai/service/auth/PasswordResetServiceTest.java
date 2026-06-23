package com.relyon.economizai.service.auth;

import com.relyon.economizai.dto.request.ForgotPasswordRequest;
import com.relyon.economizai.dto.request.ResetPasswordRequest;
import com.relyon.economizai.dto.request.VerifyResetCodeRequest;
import com.relyon.economizai.exception.InvalidAuthTokenException;
import com.relyon.economizai.model.PasswordResetToken;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.AuthProvider;
import com.relyon.economizai.repository.PasswordResetTokenRepository;
import com.relyon.economizai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

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

    @Mock private UserRepository userRepository;
    @Mock private PasswordResetTokenRepository tokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthEmailSender emailSender;

    @InjectMocks private PasswordResetService passwordResetService;

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
    void requestResetForKnownEmailInvalidatesOldCodesPersistsCodeAndEmailsIt() {
        var user = registeredUser();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        passwordResetService.requestReset(new ForgotPasswordRequest(user.getEmail()));

        // old codes invalidated before a new one is issued
        verify(tokenRepository).consumeAllActiveForUser(eq(user), any(LocalDateTime.class));

        var tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(tokenCaptor.capture());
        var savedToken = tokenCaptor.getValue();
        assertEquals(user, savedToken.getUser());
        assertTrue(savedToken.getToken().matches("\\d{6}"), "code must be exactly 6 digits");
        assertTrue(savedToken.getExpiresAt().isAfter(LocalDateTime.now()));

        // the SAME code is emailed (no link)
        verify(emailSender).sendPasswordResetCode(eq(user.getEmail()), eq(savedToken.getToken()), eq(60));
    }

    @Test
    void verifyCodeWithValidCodeSucceedsWithoutConsuming() {
        var user = registeredUser();
        var code = PasswordResetToken.builder()
                .user(user).token("123456")
                .expiresAt(LocalDateTime.now().plusMinutes(30)).build();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserAndTokenOrderByCreatedAtDesc(user, "123456"))
                .thenReturn(Optional.of(code));

        passwordResetService.verifyCode(new VerifyResetCodeRequest(user.getEmail(), "123456"));

        // verify-only: nothing consumed, no password touched
        verify(tokenRepository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void verifyCodeWithBadCodeThrows() {
        var user = registeredUser();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserAndTokenOrderByCreatedAtDesc(user, "000000"))
                .thenReturn(Optional.empty());

        assertThrows(InvalidAuthTokenException.class, () ->
                passwordResetService.verifyCode(new VerifyResetCodeRequest(user.getEmail(), "000000")));
    }

    @Test
    void resetPasswordWithValidCodeUpdatesPasswordAndConsumesCode() {
        var user = registeredUser();
        var code = PasswordResetToken.builder()
                .user(user).token("654321")
                .expiresAt(LocalDateTime.now().plusMinutes(30)).build();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserAndTokenOrderByCreatedAtDesc(user, "654321"))
                .thenReturn(Optional.of(code));
        when(passwordEncoder.encode("brand-new-password")).thenReturn("new-hash");

        passwordResetService.resetPassword(new ResetPasswordRequest(user.getEmail(), "654321", "brand-new-password"));

        assertEquals("new-hash", user.getPassword());
        verify(userRepository).save(user);
        assertNotNull(code.getConsumedAt());
        verify(tokenRepository).save(code);
    }

    @Test
    void resetPasswordWithUnknownCodeThrowsAndChangesNothing() {
        var user = registeredUser();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserAndTokenOrderByCreatedAtDesc(user, "999999"))
                .thenReturn(Optional.empty());

        assertThrows(InvalidAuthTokenException.class, () ->
                passwordResetService.resetPassword(new ResetPasswordRequest(user.getEmail(), "999999", "brand-new-password")));

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void resetPasswordWithUnknownEmailThrows() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThrows(InvalidAuthTokenException.class, () ->
                passwordResetService.resetPassword(new ResetPasswordRequest("ghost@example.com", "123456", "brand-new-password")));

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void resetPasswordWithAlreadyConsumedCodeThrows() {
        var user = registeredUser();
        var consumed = PasswordResetToken.builder()
                .user(user).token("111111")
                .expiresAt(LocalDateTime.now().plusMinutes(30))
                .consumedAt(LocalDateTime.now().minusMinutes(1)).build();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserAndTokenOrderByCreatedAtDesc(user, "111111"))
                .thenReturn(Optional.of(consumed));

        assertThrows(InvalidAuthTokenException.class, () ->
                passwordResetService.resetPassword(new ResetPasswordRequest(user.getEmail(), "111111", "brand-new-password")));

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void resetPasswordWithExpiredCodeThrows() {
        var user = registeredUser();
        var expired = PasswordResetToken.builder()
                .user(user).token("222222")
                .expiresAt(LocalDateTime.now().minusMinutes(1)).build();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserAndTokenOrderByCreatedAtDesc(user, "222222"))
                .thenReturn(Optional.of(expired));

        assertThrows(InvalidAuthTokenException.class, () ->
                passwordResetService.resetPassword(new ResetPasswordRequest(user.getEmail(), "222222", "brand-new-password")));

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
    }
}
