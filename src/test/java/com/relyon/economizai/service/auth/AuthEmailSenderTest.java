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
    private static final String RESET_CODE = "123456";
    private static final String VERIFY_LINK = "https://app.economiz.ai/verify-email?token=xyz";

    @Test
    void sendsPasswordResetCodeWhenSmtpConfigured() {
        var mailSender = mock(JavaMailSender.class);
        var sender = new AuthEmailSender(Optional.of(mailSender), FROM, "smtp-user");

        sender.sendPasswordResetCode(RECIPIENT, RESET_CODE, 60);

        var messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        var sentMessage = messageCaptor.getValue();
        assertEquals(FROM, sentMessage.getFrom());
        assertArrayEquals(new String[]{RECIPIENT}, sentMessage.getTo());
        assertEquals("Código de redefinição de senha — economizai", sentMessage.getSubject());
        assertNotNull(sentMessage.getText());
        assertTrue(sentMessage.getText().contains(RESET_CODE));
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

        sender.sendPasswordResetCode(RECIPIENT, RESET_CODE, 60);

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
        assertDoesNotThrow(() -> sender.sendPasswordResetCode(RECIPIENT, RESET_CODE, 60));
    }

    @Test
    void swallowsExceptionFromMailSenderSoCallerNeverErrors() {
        var mailSender = mock(JavaMailSender.class);
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));
        var sender = new AuthEmailSender(Optional.of(mailSender), FROM, "smtp-user");

        assertDoesNotThrow(() -> sender.sendPasswordResetCode(RECIPIENT, RESET_CODE, 60));
        verify(mailSender).send(any(SimpleMailMessage.class));
    }
}
