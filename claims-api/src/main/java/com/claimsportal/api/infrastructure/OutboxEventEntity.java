package com.claimsportal.api.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A row in {@code outbox_event}: one event waiting to be published (ADR 0002). */
@Entity
@Table(name = "outbox_event")
class OutboxEventEntity {

    /** The size of the {@code last_error} column. */
    static final int MAX_ERROR_LENGTH = 1000;

    @Id
    private UUID id;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    /** The JSON message body, stored as jsonb so it can be queried when investigating. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "trace_parent")
    private String traceParent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    /** Required by JPA. */
    protected OutboxEventEntity() {
    }

    static OutboxEventEntity pending(UUID eventId, UUID aggregateId, String eventType, int eventVersion,
            String payload, String traceParent, Instant createdAt) {
        OutboxEventEntity entity = new OutboxEventEntity();
        entity.id = eventId;
        entity.aggregateId = aggregateId;
        entity.eventType = eventType;
        entity.eventVersion = eventVersion;
        entity.payload = payload;
        entity.traceParent = traceParent;
        entity.createdAt = createdAt;
        entity.attempts = 0;
        return entity;
    }

    OutboxMessage toMessage() {
        return new OutboxMessage(id, eventType, eventVersion, payload, traceParent);
    }

    /** Called once the broker has confirmed the message. */
    void markPublished(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    /**
     * Counts a failed publish and keeps the reason. The row stays unpublished, so the relay
     * tries it again on a later run. {@code error} must not contain personal information.
     */
    void recordFailure(String error) {
        attempts++;
        lastError = error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
    }

    UUID id() {
        return id;
    }

    Instant publishedAt() {
        return publishedAt;
    }

    /** Failed publish attempts so far. */
    int attempts() {
        return attempts;
    }

    String lastError() {
        return lastError;
    }

    /** The payload holds personal information, so only identifiers are printed. */
    @Override
    public String toString() {
        return "OutboxEventEntity[id=" + id + ", type=" + eventType + "]";
    }
}
