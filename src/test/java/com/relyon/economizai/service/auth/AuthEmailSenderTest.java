package com.relyon.economizai.service.auth;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthEmailSenderTest {

    private static final String FROM = "noreply@economiz.ai";
    private static final String RECIPIENT = "maria@example.com";
    private static final String RESET_CODE = "123456";
    private static final String VERIFY_CODE = "654321";

    // A real (empty-session) MimeMessage the mocked sender hands back, so the helper
    // can populate it and we can read the rendered subject/recipients/body back.
    private JavaMailSender mailSenderReturningRealMime() {
        var mailSender = mock(JavaMailSender.class);
        var session = Session.getInstance(new Properties());
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(session));
        return mailSender;
    }

    // The most robust way to read a built MimeMessage is to serialize it: writeTo
    // renders the full MIME payload (headers + every part), which we can assert on.
    // (getContent() on a not-yet-saved message can return an unreadable tree.) The
    // HTML body is quoted-printable-encoded in the output, so decode soft line
    // breaks before asserting on substrings like the code/link.
    private String bodyOf(MimeMessage msg) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        msg.writeTo(out);
        var raw = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        // undo quoted-printable soft breaks ("=\r\n") and =XX is left as-is; for our
        // assertions (ascii code digits, ascii url) the soft-break removal suffices.
        return raw.replace("=\r\n", "").replace("=\n", "");
    }

    @Test
    void sendsPasswordResetCodeWhenSmtpConfigured() throws Exception {
        var mailSender = mailSenderReturningRealMime();
        var sender = new AuthEmailSender(Optional.of(mailSender), FROM, "smtp-user");

        sender.sendPasswordResetCode(RECIPIENT, RESET_CODE, 60);

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        var sent = captor.getValue();
        assertArrayEquals(new jakarta.mail.Address[]{new jakarta.mail.internet.InternetAddress(RECIPIENT)},
                sent.getRecipients(MimeMessage.RecipientType.TO));
        assertEquals("Seu código de redefinição de senha — economizai", sent.getSubject());
        var body = bodyOf(sent);
        assertTrue(body.contains(RESET_CODE), "rendered email must contain the code");
        assertTrue(body.contains("economizai"), "rendered email must be branded");
    }

    @Test
    void sendsEmailVerificationCodeWhenSmtpConfigured() throws Exception {
        var mailSender = mailSenderReturningRealMime();
        var sender = new AuthEmailSender(Optional.of(mailSender), FROM, "smtp-user");

        sender.sendEmailVerification(RECIPIENT, VERIFY_CODE, 24);

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        var sent = captor.getValue();
        assertEquals("Seu código de confirmação — economizai", sent.getSubject());
        var body = bodyOf(sent);
        assertTrue(body.contains(VERIFY_CODE), "rendered email must contain the code");
        assertTrue(body.contains("24 horas"), "rendered email must state the TTL");
    }

    @Test
    void doesNotSendWhenSmtpUsernameBlank() {
        var mailSender = mock(JavaMailSender.class);
        var sender = new AuthEmailSender(Optional.of(mailSender), FROM, "   ");

        sender.sendPasswordResetCode(RECIPIENT, RESET_CODE, 60);

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void doesNotSendWhenSmtpUsernameNull() {
        var mailSender = mock(JavaMailSender.class);
        var sender = new AuthEmailSender(Optional.of(mailSender), FROM, null);

        sender.sendEmailVerification(RECIPIENT, VERIFY_CODE, 24);

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void doesNotSendWhenMailSenderAbsentEvenWithUsername() {
        var sender = new AuthEmailSender(Optional.empty(), FROM, "smtp-user");

        // No mailSender bean to send through; must be a silent no-op.
        assertDoesNotThrow(() -> sender.sendPasswordResetCode(RECIPIENT, RESET_CODE, 60));
    }

    @Test
    void swallowsExceptionFromMailSenderSoCallerNeverErrors() {
        var mailSender = mailSenderReturningRealMime();
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));
        var sender = new AuthEmailSender(Optional.of(mailSender), FROM, "smtp-user");

        assertDoesNotThrow(() -> sender.sendPasswordResetCode(RECIPIENT, RESET_CODE, 60));
        verify(mailSender).send(any(MimeMessage.class));
    }
}
