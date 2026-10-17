package com.claimsportal.api.infrastructure;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import java.net.ConnectException;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * The publisher's handling of each broker outcome, with the broker's replies simulated on a
 * mocked {@link RabbitTemplate}. The integration tests cover the same paths against a real
 * RabbitMQ where they can be triggered.
 */
@ExtendWith(MockitoExtension.class)
class RabbitEventPublisherTest {

    private static final String PAYLOAD = "{\"eventType\":\"ClaimSubmitted\",\"data\":{\"email\":\"jane@example.com\"}}";

    @Mock
    private RabbitTemplate rabbitTemplate;

    private RabbitEventPublisher publisher;

    private final OutboxMessage event =
            new OutboxMessage(UUID.randomUUID(), "ClaimSubmitted", 1, PAYLOAD, null);

    @BeforeEach
    void setUp() {
        var properties = new OutboxProperties(true, Duration.ofMillis(500), 50, Duration.ofMillis(200));
        publisher = new RabbitEventPublisher(rabbitTemplate, properties);
    }

    @Test
    void sendsAPersistentJsonMessageAndReturnsOnceTheBrokerAcks() {
        brokerReplies(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null)));

        publisher.publish(event);

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<CorrelationData> correlation = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate).send(eq("claims.events"), eq("claim.submitted"), sent.capture(), correlation.capture());

        MessageProperties properties = sent.getValue().getMessageProperties();
        assertThat(properties.getMessageId()).isEqualTo(event.eventId().toString());
        assertThat(properties.getType()).isEqualTo("ClaimSubmitted");
        assertThat(properties.getContentType()).isEqualTo("application/json");
        assertThat(properties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(properties.<Integer>getHeader("eventVersion")).isEqualTo(1);
        assertThat(properties.getHeaders()).doesNotContainKey("traceparent");
        assertThat(new String(sent.getValue().getBody(), UTF_8)).isEqualTo(PAYLOAD);
        assertThat(correlation.getValue().getId()).isEqualTo(event.eventId().toString());
    }

    @Test
    void passesTheSavedTraceParentThrough() {
        String traceParent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        brokerReplies(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null)));

        publisher.publish(new OutboxMessage(event.eventId(), "ClaimSubmitted", 1, PAYLOAD, traceParent));

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(anyString(), anyString(), sent.capture(), any(CorrelationData.class));
        assertThat(sent.getValue().getMessageProperties().<String>getHeader("traceparent")).isEqualTo(traceParent);
    }

    @Test
    void failsWhenTheBrokerNacks() {
        brokerReplies(correlation -> correlation.getFuture().complete(
                new CorrelationData.Confirm(false, "internal error")));

        assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOf(EventPublishException.class)
                .hasMessage("The broker nacked the message: internal error");
    }

    @Test
    void failsWhenTheBrokerReturnsTheMessageAsUnroutable() {
        brokerReplies(correlation -> {
            // RabbitMQ sends the return before the ack, and Spring records it on the correlation
            Message body = new Message(PAYLOAD.getBytes(UTF_8));
            correlation.setReturned(new ReturnedMessage(body, 312, "NO_ROUTE", "claims.events", "claim.submitted"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOf(EventPublishException.class)
                .hasMessage("The broker returned the message as unroutable: 312 NO_ROUTE")
                .message().doesNotContain("jane@example.com");
    }

    @Test
    void failsWhenTheBrokerDoesNotConfirmInTime() {
        brokerReplies(correlation -> {
            // no confirm ever arrives
        });

        assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOf(EventPublishException.class)
                .hasMessage("No confirm from the broker within PT0.2S");
    }

    @Test
    void failsWhenTheBrokerCannotBeReached() {
        willThrow(new AmqpConnectException(new ConnectException("Connection refused")))
                .given(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOf(EventPublishException.class)
                .hasMessageStartingWith("Could not send to the broker:");
    }

    @Test
    void failsForAnEventTypeWithNoRoutingKey() {
        var unknown = new OutboxMessage(UUID.randomUUID(), "ClaimWithdrawn", 1, PAYLOAD, null);

        assertThatThrownBy(() -> publisher.publish(unknown))
                .isInstanceOf(EventPublishException.class)
                .hasMessage("No routing key for event type ClaimWithdrawn");
    }

    @Test
    void outboxMessageToStringLeavesOutThePayload() {
        assertThat(event.toString()).doesNotContain("jane@example.com");
    }

    /** Simulates the broker's reply to a send, by completing (or not) its correlation data. */
    private void brokerReplies(Consumer<CorrelationData> reply) {
        willAnswer(invocation -> {
            reply.accept(invocation.getArgument(3));
            return null;
        }).given(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }
}
