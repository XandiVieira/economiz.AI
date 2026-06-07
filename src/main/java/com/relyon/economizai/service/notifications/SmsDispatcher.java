package com.relyon.economizai.service.notifications;

import com.relyon.economizai.model.enums.NotificationChannel;
import org.springframework.stereotype.Component;

/**
 * SMS channel — structure only. A real implementation would send via Twilio or
 * a Brazilian provider (e.g. Zenvia) using the user's verified phone number.
 * Not yet functional.
 */
@Component
public class SmsDispatcher extends StubChannelDispatcher {

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SMS;
    }
}
