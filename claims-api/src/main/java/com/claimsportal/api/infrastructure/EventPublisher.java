package com.claimsportal.api.infrastructure;

/**
 * Sends one outbox event to the message broker. Kept as an interface so the relay's logic can
 * be unit-tested without a broker.
 */
interface EventPublisher {

    /**
     * Returns only once the broker has taken responsibility for the message.
     *
     * @throws EventPublishException if the message may not have reached the broker
     */
    void publish(OutboxMessage message);
}
