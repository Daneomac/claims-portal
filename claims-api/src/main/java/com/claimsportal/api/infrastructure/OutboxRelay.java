package com.claimsportal.api.infrastructure;

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

    OutboxRelay(OutboxEventRepository outbox, EventPublisher publisher, OutboxProperties properties, Clock clock) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
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
            try {
                publisher.publish(event.toMessage());
            } catch (EventPublishException e) {
                event.recordFailure(e.getMessage());
                log.warn("Could not publish outbox event {} (failed attempts: {}): {}",
                        event.id(), event.attempts(), e.getMessage());
                // Stop the batch at the first failure rather than try the rest. Failures here
                // are almost always about the broker (down, not confirming, nothing bound to
                // the exchange), not about one message, so the remaining rows would fail too,
                // each one waiting out the confirm timeout while this transaction holds its
                // locks. Stopping also keeps events roughly in the order they happened. The
                // rows published before the failure are still committed.
                break;
            }
            event.markPublished(clock.instant());
            published++;
            log.debug("Published outbox event {}", event.id());
        }
        return published;
    }
}
