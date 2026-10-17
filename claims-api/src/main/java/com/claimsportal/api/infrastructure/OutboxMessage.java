package com.claimsportal.api.infrastructure;

import java.util.UUID;

/**
 * An outbox event as the publisher needs it, so the publisher knows nothing about JPA.
 *
 * @param eventId      the event ID, sent as the AMQP messageId so consumers can de-duplicate
 * @param eventType    e.g. {@code ClaimSubmitted}
 * @param eventVersion the contract version of the payload
 * @param payload      the JSON message body
 * @param traceParent  the W3C traceparent saved with the event, or null
 */
record OutboxMessage(UUID eventId, String eventType, int eventVersion, String payload, String traceParent) {

    /** The payload holds personal information, so only identifiers are printed. */
    @Override
    public String toString() {
        return "OutboxMessage[eventId=" + eventId + ", type=" + eventType + "]";
    }
}
