package com.claimsportal.notification.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The {@code processed_event} table: which events have already been handled (ADR 0003). */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    /**
     * Records the event as handled, unless it already is. {@code ON CONFLICT DO NOTHING}
     * reports a duplicate as "0 rows inserted" instead of an error, so the caller's
     * transaction stays usable.
     *
     * <p>If another transaction has inserted the same event ID but not committed yet, this
     * insert waits for it to finish. Then it returns 0 if that transaction committed, or
     * inserts the row and returns 1 if it rolled back.
     *
     * <p>Must run inside the caller's transaction ({@code MANDATORY}): the row is only
     * useful if it commits or rolls back together with the work it stands for.
     *
     * @return 1 if this call recorded the event, 0 if it had already been recorded
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = """
            INSERT INTO processed_event (event_id, claim_number, processed_at)
            VALUES (:eventId, :claimNumber, :processedAt)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(UUID eventId, String claimNumber, Instant processedAt);
}
