package com.relyon.economizai.service.notifications;

import com.relyon.economizai.model.enums.NotificationChannel;
import com.relyon.economizai.service.notifications.twilio.TwilioMessageClient;
import com.relyon.economizai.service.notifications.twilio.TwilioMessageException;
import com.relyon.economizai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * WhatsApp channel backed by Twilio's Messages API ({@code whatsapp:}-prefixed
 * From/To). Delivers only when Twilio's WhatsApp From is configured AND the user
 * has a verified phone number; otherwise it degrades gracefully — records a clear
 * "not delivered" reason and never throws.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsAppDispatcher implements NotificationDispatcher {

    private final TwilioMessageClient twilioMessageClient;

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.WHATSAPP;
    }

    @Override
    public DispatchResult dispatch(NotificationPayload payload) {
        var user = payload.user();
        if (!twilioMessageClient.isConfigured(true)) {
            log.info("notification.whatsapp.skipped user={} type={} reason=twilio_not_configured",
                    LogMasker.email(user.getEmail()), payload.type());
            return DispatchResult.failed("twilio_not_configured");
        }
        if (!user.isPhoneVerified() || user.getPhoneNumber() == null || user.getPhoneNumber().isBlank()) {
            log.info("notification.whatsapp.skipped user={} type={} reason=phone_not_verified",
                    LogMasker.email(user.getEmail()), payload.type());
            return DispatchResult.failed("phone_not_verified");
        }
        try {
            twilioMessageClient.sendWhatsApp(user.getPhoneNumber(), payload.body());
            log.info("notification.whatsapp.sent user={} phone={} type={}",
                    LogMasker.email(user.getEmail()), LogMasker.phone(user.getPhoneNumber()), payload.type());
            return DispatchResult.ok();
        } catch (TwilioMessageException ex) {
            log.warn("notification.whatsapp.failed user={} phone={} type={} {}",
                    LogMasker.email(user.getEmail()), LogMasker.phone(user.getPhoneNumber()),
                    payload.type(), ex.getMessage());
            return DispatchResult.failed(ex.getMessage());
        }
    }
}
