package com.claimsportal.api.infrastructure;

/**
 * A publish that the broker didn't confirm. The message is saved in {@code last_error}, so it
 * must describe the failure only and never include the event payload.
 */
class EventPublishException extends RuntimeException {

    EventPublishException(String message) {
        super(message);
    }

    EventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
