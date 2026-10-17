package com.claimsportal.notification.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A {@code ClaimSubmitted} event that has been handled: its confirmation email was sent.
 * Rows are written by {@link ProcessedEventRepository#insertIfAbsent}; the entity is for
 * reading them back (and lets Hibernate validate the table against the mapping on start-up).
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "claim_number", nullable = false)
    private String claimNumber;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
        // for JPA
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getClaimNumber() {
        return claimNumber;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
