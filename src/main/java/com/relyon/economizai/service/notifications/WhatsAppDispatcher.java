package com.relyon.economizai.service.notifications;

import com.relyon.economizai.model.enums.NotificationChannel;
import org.springframework.stereotype.Component;

/**
 * WhatsApp channel — structure only. A real implementation would send a
 * template message via the WhatsApp Cloud API (Meta) to the user's verified
 * number. Not yet functional.
 */
@Component
public class WhatsAppDispatcher extends StubChannelDispatcher {

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.WHATSAPP;
    }
}
