package com.claimsportal.notification;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds {@code ClaimSubmitted} messages the way claims-api publishes them (PLAN section 5),
 * starting from the contract fixture. Each one gets its own event ID, claim number and email
 * address, so tests don't see each other's emails.
 */
final class TestEvents {

    static final String EXCHANGE = "claims.events";
    static final String ROUTING_KEY = "claim.submitted";
    static final String DEAD_LETTER_QUEUE = "notification.claim-submitted.dlq";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TestEvents() {
    }

    /** One customer's claim, and the event that announces it. */
    record TestClaim(UUID eventId, String claimNumber, String firstName, String email) {

        static TestClaim random() {
            UUID eventId = UUID.randomUUID();
            String claimNumber = "HC-2026-%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000));
            String email = "customer-" + eventId.toString().substring(0, 8) + "@example.com";
            return new TestClaim(eventId, claimNumber, "Jeanette", email);
        }

        Message message() {
            return message(1);
        }

        Message message(int eventVersion) {
            ObjectNode event = fixture();
            event.put("eventId", eventId.toString());
            event.put("eventVersion", eventVersion);
            ObjectNode data = (ObjectNode) event.get("data");
            data.put("claimNumber", claimNumber);
            ObjectNode policyholder = (ObjectNode) data.get("policyholder");
            policyholder.put("firstName", firstName);
            policyholder.put("email", email);
            return amqpMessage(JSON.writeValueAsString(event), eventId.toString(), eventVersion);
        }
    }

    /** A message with the producer's properties around any body, e.g. one that isn't JSON. */
    static Message amqpMessage(String body, String messageId, int eventVersion) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId);
        properties.setType("ClaimSubmitted");
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setHeader("eventVersion", eventVersion);
        return new Message(body.getBytes(UTF_8), properties);
    }

    private static ObjectNode fixture() {
        try {
            return (ObjectNode) JSON.readTree(
                    new ClassPathResource("contracts/claim-submitted-v1.json").getContentAsString(UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
