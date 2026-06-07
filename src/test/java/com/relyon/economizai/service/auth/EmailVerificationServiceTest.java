package com.relyon.economizai.service.auth;

import com.relyon.economizai.exception.InvalidAuthTokenException;
import com.relyon.economizai.model.EmailVerificationToken;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.EmailVerificationTokenRepository;
import com.relyon.economizai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
class EmailVerificationServiceTest {

    private static final String FRONTEND_BASE_URL = "https://app.economiz.ai";

    @Mock private UserRepository userRepository;
    @Mock private EmailVerificationTokenRepository tokenRepository;
    @Mock private AuthEmailSender emailSender;

    @InjectMocks private EmailVerificationService emailVerificationService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(emailVerificationService, "frontendBaseUrl", FRONTEND_BASE_URL);
    }

    private User unverifiedUser() {
        return User.builder().id(UUID.randomUUID()).email("maria@example.com").emailVerified(false).build();
    }

    @Test
    void sendVerificationForUnverifiedUserPersistsTokenAndSendsLink() {
        var user = unverifiedUser();

        emailVerificationService.sendVerificationFor(user);

        var tokenCaptor = ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(tokenRepository).save(tokenCaptor.capture());
        var savedToken = tokenCaptor.getValue();
        assertEquals(user, savedToken.getUser());
        assertNotNull(savedToken.getToken());
        assertTrue(savedToken.getExpiresAt().isAfter(LocalDateTime.now()));

        var linkCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendEmailVerification(eq(user.getEmail()), linkCaptor.capture());
        assertTrue(linkCaptor.getValue().startsWith(FRONTEND_BASE_URL + "/verify-email?token="));
        assertTrue(linkCaptor.getValue().contains(savedToken.getToken()));
    }

    @Test
    void sendVerificationGeneratesUrlSafeTokenWithoutPadding() {
        var user = unverifiedUser();

        emailVerificationService.sendVerificationFor(user);

        var tokenCaptor = ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(tokenRepository).save(tokenCaptor.capture());
        assertTrue(tokenCaptor.getValue().getToken().matches("[A-Za-z0-9_-]+"),
                "token must be url-safe base64 without padding");
    }

    @Test
    void sendVerificationForAlreadyVerifiedUserIsNoOp() {
        var user = User.builder().id(UUID.randomUUID()).email("maria@example.com").emailVerified(true).build();

        emailVerificationService.sendVerificationFor(user);

        verifyNoInteractions(tokenRepository);
        verifyNoInteractions(emailSender);
    }

    @Test
    void resendForUnverifiedUserDelegatesToSend() {
        var user = unverifiedUser();

        emailVerificationService.resend(user);

        verify(tokenRepository).save(any(EmailVerificationToken.class));
        verify(emailSender).sendEmailVerification(eq(user.getEmail()), anyString());
    }

    @Test
    void resendForVerifiedUserIsSilentNoOp() {
        var user = User.builder().id(UUID.randomUUID()).email("maria@example.com").emailVerified(true).build();

        emailVerificationService.resend(user);

        verifyNoInteractions(tokenRepository);
        verifyNoInteractions(emailSender);
    }

    @Test
    void verifyWithValidTokenMarksUserVerifiedAndConsumesToken() {
        var user = unverifiedUser();
        var token = EmailVerificationToken.builder()
                .user(user)
                .token("valid-token")
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build();
        when(tokenRepository.findByToken("valid-token")).thenReturn(Optional.of(token));

        emailVerificationService.verify("valid-token");

        assertTrue(user.isEmailVerified());
        assertNotNull(user.getEmailVerifiedAt());
        verify(userRepository).save(user);
        assertNotNull(token.getConsumedAt());
        verify(tokenRepository).save(token);
    }

    @Test
    void verifyWithUnknownTokenThrowsAndChangesNothing() {
        when(tokenRepository.findByToken("missing")).thenReturn(Optional.empty());

        assertThrows(InvalidAuthTokenException.class, () -> emailVerificationService.verify("missing"));

        verify(userRepository, never()).save(any());
    }

    @Test
    void verifyWithConsumedTokenThrows() {
        var user = unverifiedUser();
        var token = EmailVerificationToken.builder()
                .user(user)
                .token("consumed-token")
                .expiresAt(LocalDateTime.now().plusHours(1))
                .consumedAt(LocalDateTime.now().minusMinutes(5))
                .build();
        when(tokenRepository.findByToken("consumed-token")).thenReturn(Optional.of(token));

        assertThrows(InvalidAuthTokenException.class, () -> emailVerificationService.verify("consumed-token"));

        assertFalse(user.isEmailVerified());
        verify(userRepository, never()).save(any());
    }

    @Test
    void verifyWithExpiredTokenThrows() {
        var user = unverifiedUser();
        var token = EmailVerificationToken.builder()
                .user(user)
                .token("expired-token")
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .build();
        when(tokenRepository.findByToken("expired-token")).thenReturn(Optional.of(token));

        assertThrows(InvalidAuthTokenException.class, () -> emailVerificationService.verify("expired-token"));

        assertFalse(user.isEmailVerified());
        verify(userRepository, never()).save(any());
    }
}
