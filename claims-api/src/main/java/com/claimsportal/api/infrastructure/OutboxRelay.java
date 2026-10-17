package com.claimsportal.api.infrastructure;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves events from the outbox table to the broker (ADR 0002). Each run locks a batch of the
 * oldest unpublished rows, publishes them one at a time, and marks a row published only after
 * the broker has confirmed it.
 *
 * <p>Delivery is at-least-once: if the process dies after the broker confirms but before the
 * transaction commits, the row is still unpublished and is sent again on the next run. The
 * consumer de-duplicates by event ID (ADR 0003).
 */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outbox;
    private final EventPublisher publisher;
    private final OutboxProperties properties;
    private final Clock clock;
    private final OutboxTracing tracing;

    OutboxRelay(OutboxEventRepository outbox, EventPublisher publisher, OutboxProperties properties, Clock clock,
            OutboxTracing tracing) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
        this.tracing = tracing;
    }

    /**
     * Publishes up to one batch of pending events. The row locks are held until this method
     * returns, and the changes to the rows are saved when its transaction commits.
     *
     * @return how many events were published
     */
    @Transactional
    public int publishPendingEvents() {
        List<OutboxEventEntity> batch = outbox.lockNextBatch(properties.batchSize());
        int published = 0;
        for (OutboxEventEntity event : batch) {
            OutboxMessage message = event.toMessage();
            // Publish inside the trace of the request that saved the event, so the log lines
            // below, the RabbitMQ send and the consumer all share its trace ID (OutboxTracing)
            Span span = tracing.startPublishSpan(message.traceParent(), message.eventId());
            try (Tracer.SpanInScope scope = tracing.withSpan(span)) {
                if (!publish(event, message, span)) {
                    // Stop the batch at the first failure rather than try the rest. Failures
                    // here are almost always about the broker (down, not confirming, nothing
                    // bound to the exchange), not about one message, so the remaining rows
                    // would fail too, each one waiting out the confirm timeout while this
                    // transaction holds its locks. Stopping also keeps events roughly in the
                    // order they happened. The rows published before the failure are still
                    // committed.
                    break;
                }
            } finally {
                span.end();
            }
            published++;
        }
        return published;
    }

    /** Sends one event and records the outcome on its row. Returns whether the broker confirmed it. */
    private boolean publish(OutboxEventEntity event, OutboxMessage message, Span span) {
        try {
            publisher.publish(message);
        } catch (EventPublishException e) {
            event.recordFailure(e.getMessage());
            span.error(e);
            log.warn("Could not publish outbox event {} (failed attempts: {}): {}",
                    event.id(), event.attempts(), e.getMessage());
            return false;
        }
        event.markPublished(clock.instant());
        log.info("Published outbox event {}", event.id());
        return true;
    }
}
