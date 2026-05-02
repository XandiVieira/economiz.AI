package com.relyon.economizai.service.auth;

import com.relyon.economizai.service.privacy.LogMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Sends auth-flow emails (password reset, verification) directly — these
 * MUST go out regardless of the per-user notification preferences. If
 * SMTP isn't configured (dev), logs the link with a clear DEV-MODE
 * marker so the developer can copy it from the logs and finish the flow.
 *
 * <p>Distinct from {@code EmailDispatcher} which is preference-driven and
 * conditional on {@code notifications.email.enabled}.
 */
@Slf4j
@Component
public class AuthEmailSender {

    private final Optional<JavaMailSender> mailSender;
    private final String from;
    private final boolean smtpConfigured;

    public AuthEmailSender(Optional<JavaMailSender> mailSender,
                           @Value("${economizai.notifications.email.from:noreply@economiz.ai}") String from,
                           @Value("${spring.mail.username:}") String smtpUsername) {
        this.mailSender = mailSender;
        this.from = from;
        this.smtpConfigured = mailSender.isPresent() && smtpUsername != null && !smtpUsername.isBlank();
    }

    public void sendPasswordReset(String email, String resetLink) {
        send(email,
                "Redefinição de senha — economizai",
                "Você solicitou uma redefinição de senha. Abra o link abaixo (válido por 1 hora):\n\n" + resetLink
                        + "\n\nSe não foi você, ignore este e-mail.",
                "password-reset");
    }

    public void sendEmailVerification(String email, String verifyLink) {
        send(email,
                "Confirme seu e-mail — economizai",
                "Bem-vindo ao economizai! Confirme seu e-mail no link abaixo (válido por 24 horas):\n\n" + verifyLink,
                "email-verification");
    }

    private void send(String to, String subject, String body, String purpose) {
        if (!smtpConfigured) {
            // DEV-MODE: SMTP creds not wired. Log the would-be email so the
            // developer can copy the link and continue the flow. Documented
            // in DEV_NOTES.md as a "wire SMTP before prod" item.
            log.warn("[DEV-MODE] {} email NOT sent to {} (SMTP not configured). body:\n{}",
                    purpose, LogMasker.email(to), body);
            return;
        }
        try {
            var message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.get().send(message);
            log.info("auth_email.sent purpose={} to={}", purpose, LogMasker.email(to));
        } catch (Exception ex) {
            log.warn("auth_email.failed purpose={} to={} {}: {}",
                    purpose, LogMasker.email(to), ex.getClass().getSimpleName(), ex.getMessage());
            // Don't propagate — the user shouldn't get an error if SMTP is
            // having a bad day. The token is already persisted; they can
            // request another reset link.
        }
    }
}
