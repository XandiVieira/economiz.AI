package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.enums.DigestFrequency;

/**
 * The user's current deals-digest delivery preferences (Phase C). {@code sendHour}
 * is null when the user hasn't overridden it (the scheduler infers it).
 */
public record DigestPreferencesResponse(
        DigestFrequency frequency,
        Integer sendHour
) {}
