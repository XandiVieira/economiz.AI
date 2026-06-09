package com.relyon.economizai.repository;

import com.relyon.economizai.model.NotificationEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.UUID;

public interface NotificationEventRepository extends JpaRepository<NotificationEvent, UUID> {

    /**
     * North-star rollup (Phase D): total realized R$ savings and conversion count
     * from {@code CONVERTED} events across a household's users, optionally since a
     * cutoff. {@code COALESCE} keeps the sum at zero when there are no rows.
     */
    @Query("""
        SELECT COALESCE(SUM(event.savingsAmount), 0) AS totalSavings, COUNT(event) AS conversions
        FROM NotificationEvent event
        WHERE event.user.id IN :userIds
          AND event.eventType = 'CONVERTED'
          AND (:since IS NULL OR event.occurredAt >= :since)
    """)
    SavingsRollup sumConvertedSavings(@Param("userIds") Collection<UUID> userIds,
                                      @Param("since") OffsetDateTime since);

    /** Projection for {@link #sumConvertedSavings}. */
    interface SavingsRollup {
        BigDecimal getTotalSavings();
        long getConversions();
    }
}
