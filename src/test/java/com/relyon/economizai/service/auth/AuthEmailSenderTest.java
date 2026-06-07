package com.relyon.economizai.service.auth;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AuthEmailSenderTest {

    private static final String FROM = "noreply@economiz.ai";
    private static final String RECIPIENT = "maria@example.com";
    private static final String RESET_LINK = "https://app.economiz.ai/reset-password?token=abc";
    private static final String VERIFY_LINK = "https://app.economiz.ai/verify-email?token=xyz";

    @Test
    void sendsPasswordResetWhenSmtpConfigured() {
        var mailSender = mock(JavaMailSender.class);
        var sender = new AuthEmailSender(Optional.of(mailSender), FROM, "smtp-user");

        sender.sendPasswordReset(RECIPIENT, RESET_LINK);

        var messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        var sentMessage = messageCaptor.getValue();
        assertEquals(FROM, sentMessage.getFrom());
        assertArrayEquals(new String[]{RECIPIENT}, sentMessage.getTo());
        assertEquals("Redefinição de senha — economizai", sentMessage.getSubject());
        assertNotNull(sentMessage.getText());
        assertTrue(sentMessage.getText().contains(RESET_LINK));
    }

    @Test
    void sendsEmailVerificationWhenSmtpConfigured() {
        var mailSender = mock(JavaMailSender.class);
        var sender = new AuthEmailSender(Optional.of(mailSender), FROM, "smtp-user");

        sender.sendEmailVerification(RECIPIENT, VERIFY_LINK);

        var messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        var sentMessage = messageCaptor.getValue();
        assertArrayEquals(new String[]{RECIPIENT}, sentMessage.getTo());
        assertEquals("Confirme seu e-mail — economizai", sentMessage.getSubject());
        assertTrue(sentMessage.getText().contains(VERIFY_LINK));
    }

    @Test
    void doesNotSendWhenSmtpUsernameBlank() {
        var mailSender = mock(JavaMailSender.class);
        var sender = new AuthEmailSender(Optional.of(mailSender), FROM, "   ");

        sender.sendPasswordReset(RECIPIENT, RESET_LINK);

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void doesNotSendWhenSmtpUsernameNull() {
        var mailSender = mock(JavaMailSender.class);
        var sender = new AuthEmailSender(Optional.of(mailSender), FROM, null);

        sender.sendEmailVerification(RECIPIENT, VERIFY_LINK);

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void doesNotSendWhenMailSenderAbsentEvenWithUsername() {
        var sender = new AuthEmailSender(Optional.empty(), FROM, "smtp-user");

        // No mailSender bean to send through; must be a silent no-op.
        assertDoesNotThrow(() -> sender.sendPasswordReset(RECIPIENT, RESET_LINK));
    }

    @Test
    void swallowsExceptionFromMailSenderSoCallerNeverErrors() {
        var mailSender = mock(JavaMailSender.class);
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));
        var sender = new AuthEmailSender(Optional.of(mailSender), FROM, "smtp-user");

        assertDoesNotThrow(() -> sender.sendPasswordReset(RECIPIENT, RESET_LINK));
        verify(mailSender).send(any(SimpleMailMessage.class));
    }
}
