package com.claimsportal.api.infrastructure;

import com.claimsportal.api.application.EventOutbox;
import com.claimsportal.api.domain.ClaimSubmitted;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes events to {@code outbox_event} in the caller's transaction. Publishing to RabbitMQ
 * is done later by {@link OutboxRelay}. The current trace context is saved with each event,
 * so the publish continues the request's trace ({@link OutboxTracing}).
 */
@Repository
class JpaEventOutbox implements EventOutbox {

    private final EntityManager entityManager;
    private final JsonMapper jsonMapper;
    private final OutboxTracing tracing;

    JpaEventOutbox(EntityManager entityManager, JsonMapper jsonMapper, OutboxTracing tracing) {
        this.entityManager = entityManager;
        this.jsonMapper = jsonMapper;
        this.tracing = tracing;
    }

    /** MANDATORY: fail loudly if there's no transaction, rather than save the event on its own. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(ClaimSubmitted event) {
        String payload = jsonMapper.writeValueAsString(ClaimSubmittedMessage.from(event));
        entityManager.persist(OutboxEventEntity.pending(
                event.eventId(),
                event.claimId(),
                ClaimSubmitted.TYPE,
                ClaimSubmitted.VERSION,
                payload,
                tracing.currentTraceParent(), // e.g. the HTTP request's trace; null if not traced
                event.occurredAt()));
    }
}
