package com.claimsportal.api.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Reads the outbox for the relay and the {@code outbox.pending} gauge. */
interface OutboxEventRepository extends Repository<OutboxEventEntity, UUID> {

    /**
     * Locks the oldest unpublished events, up to {@code batchSize}, until the caller's
     * transaction ends. {@code SKIP LOCKED} passes over rows another relay instance has
     * already locked, so several API instances can relay at once without publishing the same
     * row at the same time. Uses the partial index on unpublished rows.
     */
    @Query(value = """
            SELECT * FROM outbox_event
            WHERE published_at IS NULL
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEventEntity> lockNextBatch(int batchSize);

    long countByPublishedAtIsNull();
}
