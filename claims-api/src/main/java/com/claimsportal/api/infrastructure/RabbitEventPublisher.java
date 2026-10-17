package com.claimsportal.api.infrastructure;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.claimsportal.api.domain.ClaimSubmitted;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes outbox events to the {@code claims.events} exchange and waits for the broker to
 * confirm each one (PLAN section 5 has the message format).
 *
 * <p>A publish only counts as successful when the broker acks it <em>and</em> didn't return
 * it. Publisher confirms ({@code publisher-confirm-type: correlated}) tell us the broker has
 * the message; with {@code mandatory} set, a message that matched no queue comes back as a
 * "return" before the ack, because an ack alone would also be sent for a message that was
 * routed nowhere and dropped.
 */
@Component
class RabbitEventPublisher implements EventPublisher {

    static final String EVENT_VERSION_HEADER = "eventVersion";
    static final String TRACEPARENT_HEADER = "traceparent";

    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;

    RabbitEventPublisher(RabbitTemplate rabbitTemplate, OutboxProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeout = properties.confirmTimeout();
    }

    @Override
    public void publish(OutboxMessage event) {
        String routingKey = routingKeyFor(event.eventType());
        CorrelationData correlation = new CorrelationData(event.eventId().toString());
        try {
            rabbitTemplate.send(MessagingConfiguration.CLAIMS_EVENTS_EXCHANGE, routingKey, toAmqpMessage(event),
                    correlation);
        } catch (AmqpException e) {
            // e.g. no connection to the broker; the exception describes the connection, not the message
            throw new EventPublishException("Could not send to the broker: " + e.getMessage(), e);
        }
        awaitConfirm(correlation);
    }

    private static Message toAmqpMessage(OutboxMessage event) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(event.eventId().toString());
        properties.setType(event.eventType());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT); // written to disk on durable queues
        properties.setHeader(EVENT_VERSION_HEADER, event.eventVersion());
        if (event.traceParent() != null) {
            properties.setHeader(TRACEPARENT_HEADER, event.traceParent());
        }
        return new Message(event.payload().getBytes(UTF_8), properties);
    }

    /** One routing key per event type, so consumers can bind to just the events they want. */
    private static String routingKeyFor(String eventType) {
        return switch (eventType) {
            case ClaimSubmitted.TYPE -> "claim.submitted";
            default -> throw new EventPublishException("No routing key for event type " + eventType);
        };
    }

    private void awaitConfirm(CorrelationData correlation) {
        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new EventPublishException("No confirm from the broker within " + confirmTimeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublishException("Interrupted while waiting for the broker's confirm", e);
        } catch (ExecutionException e) {
            throw new EventPublishException("Waiting for the broker's confirm failed: " + e.getCause(), e);
        }

        if (!confirm.ack()) {
            throw new EventPublishException("The broker nacked the message: " + confirm.reason());
        }
        ReturnedMessage returned = correlation.getReturned();
        if (returned != null) {
            // Only the reply code and text: ReturnedMessage.toString() would include the body
            throw new EventPublishException("The broker returned the message as unroutable: "
                    + returned.getReplyCode() + " " + returned.getReplyText());
        }
    }
}
