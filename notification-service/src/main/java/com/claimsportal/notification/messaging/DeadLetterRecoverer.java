package com.claimsportal.notification.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.core.NestedExceptionUtils;

/**
 * Called by the listener's retry interceptor when it gives up on a message: retries are used
 * up, or the failure isn't retryable. It counts and logs the failure, then rejects the message
 * without requeueing it, so the broker routes it to the dead-letter queue (ADR 0004).
 *
 * <p>Spring AMQP's own {@code RejectAndDontRequeueRecoverer} does the same rejection, but
 * logs the failure's full stack trace, whose messages can hold personal information (an SMTP
 * error can quote the recipient's address, a JSON error can quote the input), and passes the
 * failure on as the cause of its exception, so the container logs it again. It also logs
 * {@code Message.toString()}, which prints a JSON body only up to 50 bytes
 * ({@code Message.setMaxBodyLength}); an event is longer, so the body itself isn't printed.
 * This one logs the message ID (the event ID) and the exception type only, and the exception
 * it throws has no cause, so the container's own warning doesn't print the original error either.
 */
class DeadLetterRecoverer implements MessageRecoverer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterRecoverer.class);

    private final Counter failed;

    DeadLetterRecoverer(MeterRegistry meterRegistry) {
        this.failed = Counter.builder("notifications.failed")
                .description("Events dead-lettered without a confirmation email being sent")
                .register(meterRegistry);
    }

    @Override
    public void recover(Message message, Throwable cause) {
        String eventId = message.getMessageProperties().getMessageId();
        failed.increment();
        log.error("Giving up on event {}: {}. It goes to the dead-letter queue {}", eventId, describe(cause),
                MessagingConfiguration.DEAD_LETTER_QUEUE);
        throw new AmqpRejectAndDontRequeueException("Event " + eventId + " was dead-lettered");
    }

    /**
     * Our own {@link UnprocessableEventException} messages are written to be safe to log. Any
     * other message might not be (an SMTP error can quote the recipient's address, a JSON
     * error can quote the input), so for those only the exception type is logged.
     */
    private static String describe(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof UnprocessableEventException unprocessable) {
                return unprocessable.getMessage();
            }
        }
        return NestedExceptionUtils.getMostSpecificCause(cause).getClass().getName();
    }
}
