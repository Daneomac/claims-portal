package com.claimsportal.notification.messaging;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;

/**
 * The message can never be processed, however often it's retried: an unsupported event
 * version, or a payload that fails validation. It goes straight to the dead-letter queue
 * (ADR 0004).
 *
 * <p>Two things make that happen. The listener's retry policy excludes this type, so it isn't
 * retried ({@link MessagingConfiguration#nonRetryableExceptions}). And because it's an
 * {@link AmqpRejectAndDontRequeueException}, the container rejects the message without
 * requeueing it even if retry were switched off.
 *
 * <p>Messages are logged, so they must never contain personal information.
 */
class UnprocessableEventException extends AmqpRejectAndDontRequeueException {

    UnprocessableEventException(String message) {
        super(message);
    }
}
