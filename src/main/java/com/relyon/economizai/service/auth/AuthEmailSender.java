package com.relyon.economizai.service.auth;

import com.relyon.economizai.service.privacy.LogMasker;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Sends auth-flow emails (password reset, verification) directly — these
 * MUST go out regardless of the per-user notification preferences. If
 * SMTP isn't configured (dev), logs the content with a clear DEV-MODE
 * marker so the developer can copy it from the logs and finish the flow.
 *
 * <p>Emails are sent as branded HTML (with a plain-text fallback for
 * clients that don't render HTML). Distinct from {@code EmailDispatcher}
 * which is preference-driven and conditional on {@code notifications.email.enabled}.
 */
@Slf4j
@Component
public class AuthEmailSender {

    private final Optional<JavaMailSender> mailSender;
    private final String from;
    private final boolean smtpConfigured;
    private final boolean devCodeLogEnabled;

    public AuthEmailSender(Optional<JavaMailSender> mailSender,
                           @Value("${economizai.notifications.email.from:noreply@economizaai.app}") String from,
                           @Value("${spring.mail.username:}") String smtpUsername,
                           @Value("${economizai.auth.dev-code-log-enabled:true}") boolean devCodeLogEnabled) {
        this.mailSender = mailSender;
        this.from = from;
        this.smtpConfigured = mailSender.isPresent() && smtpUsername != null && !smtpUsername.isBlank();
        this.devCodeLogEnabled = devCodeLogEnabled;
    }

    public void sendPasswordResetCode(String email, String code, int ttlMinutes) {
        var text = "Você solicitou uma redefinição de senha. Use o código abaixo no app (válido por "
                + ttlMinutes + " minutos):\n\n" + code
                + "\n\nSe não foi você, ignore este e-mail.";
        var html = codeEmailHtml(
                "Redefinição de senha",
                "Você solicitou a redefinição da sua senha. Use o código abaixo no app:",
                code,
                "Este código é válido por " + ttlMinutes + " minutos e só pode ser usado uma vez.",
                "Se não foi você, ignore este e-mail — sua senha continua a mesma.");
        send(email, "Seu código de redefinição de senha — economizai", text, html, "password-reset");
    }

    public void sendEmailVerification(String email, String code, int ttlHours) {
        var text = "Bem-vindo ao economizai! Use o código abaixo no app para confirmar seu e-mail (válido por "
                + ttlHours + " horas):\n\n" + code;
        var html = codeEmailHtml(
                "Confirme seu e-mail",
                "Bem-vindo ao economizai! Use o código abaixo no app para concluir o cadastro:",
                code,
                "Este código é válido por " + ttlHours + " horas e só pode ser usado uma vez.",
                "Se você não criou esta conta, ignore este e-mail.");
        send(email, "Seu código de confirmação — economizai", text, html, "email-verification");
    }

    private void send(String to, String subject, String text, String html, String purpose) {
        if (!smtpConfigured) {
            if (devCodeLogEnabled) {
                // DEV-MODE: SMTP creds not wired. Log the would-be email so the
                // developer can copy the code/link and continue the flow. Documented
                // in DEV_NOTES.md as a "wire SMTP before prod" item.
                log.warn("[DEV-MODE] {} email NOT sent to {} (SMTP not configured). body:\n{}",
                        purpose, LogMasker.email(to), text);
            } else {
                // Prod: a reset/verify code in the logs is an account-takeover
                // vector — record the misconfiguration, never the code.
                log.error("auth_email.not_sent purpose={} to={} reason=smtp_not_configured",
                        purpose, LogMasker.email(to));
            }
            return;
        }
        try {
            var sender = mailSender.get();
            MimeMessage message = sender.createMimeMessage();
            // multipart so we can carry BOTH the HTML and a plain-text fallback.
            var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, html);   // (plain, html) — clients pick what they can render
            sender.send(message);
            log.info("auth_email.sent purpose={} to={}", purpose, LogMasker.email(to));
        } catch (Exception ex) {
            log.warn("auth_email.failed purpose={} to={} {}: {}",
                    purpose, LogMasker.email(to), ex.getClass().getSimpleName(), ex.getMessage());
            // Don't propagate — the user shouldn't get an error if SMTP is
            // having a bad day. The token is already persisted; they can
            // request another reset link.
        }
    }

    // Branded HTML for a CODE email (big, copy-friendly code block). Inline styles
    // only — email clients strip <style>/external CSS. Table layout + max 600px is
    // the email-safe convention that renders consistently across Gmail/Outlook/Apple.
    private String codeEmailHtml(String heading, String intro, String code, String validity, String disclaimer) {
        var codeBlock = """
                <div style="margin:28px 0;text-align:center;">
                  <div style="display:inline-block;background:#f1f5f9;border:1px solid #e2e8f0;border-radius:12px;
                              padding:18px 32px;font-family:'Courier New',Courier,monospace;font-size:34px;
                              font-weight:700;letter-spacing:10px;color:#0f172a;">%s</div>
                </div>
                <p style="margin:0 0 8px;color:#64748b;font-size:13px;text-align:center;">%s</p>
                """.formatted(escape(code), escape(validity));
        return shell(heading, intro, codeBlock, disclaimer);
    }

    // Shared outer shell: header band, white card, content, footer. One green brand
    // accent (#16a34a) to match the app, neutral slate text, centered 600px card.
    private String shell(String heading, String intro, String content, String footnote) {
        return """
                <!DOCTYPE html>
                <html lang="pt-BR"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background:#f1f5f9;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f1f5f9;padding:32px 12px;">
                    <tr><td align="center">
                      <table role="presentation" width="600" cellpadding="0" cellspacing="0"
                             style="max-width:600px;width:100%%;background:#ffffff;border-radius:16px;overflow:hidden;
                                    box-shadow:0 1px 3px rgba(0,0,0,0.08);font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;">
                        <tr><td style="background:#16a34a;padding:24px 32px;">
                          <span style="color:#ffffff;font-size:22px;font-weight:700;letter-spacing:-0.5px;">economizai</span>
                        </td></tr>
                        <tr><td style="padding:32px 32px 8px;">
                          <h1 style="margin:0 0 12px;color:#0f172a;font-size:20px;font-weight:700;">%s</h1>
                          <p style="margin:0;color:#334155;font-size:15px;line-height:1.6;">%s</p>
                          %s
                          <p style="margin:16px 0 0;color:#94a3b8;font-size:13px;line-height:1.6;">%s</p>
                        </td></tr>
                        <tr><td style="padding:24px 32px 32px;">
                          <hr style="border:none;border-top:1px solid #e2e8f0;margin:0 0 16px;">
                          <p style="margin:0;color:#94a3b8;font-size:12px;line-height:1.5;">
                            economizai — inteligência de preços a partir das suas notas fiscais.<br>
                            Este é um e-mail automático, não é necessário respondê-lo.
                          </p>
                        </td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body></html>
                """.formatted(escape(heading), escape(intro), content, footnote);
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
