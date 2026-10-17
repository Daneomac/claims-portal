package com.claimsportal.notification.messaging;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The consumer side of the event contract test (ADR 0005).
 *
 * <p>{@code contracts/claim-submitted-v1.json} is a copy of claims-api's fixture, which the
 * producer's test compares its published messages with. If the two copies are kept the same,
 * a producer change that breaks this service fails one of the two tests.
 *
 * <p>The body is converted with the same message converter the listener uses, the way the
 * listener container calls it (the target type is inferred from the listener's parameter).
 */
class ClaimSubmittedContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MessageConverter converter = new MessagingConfiguration().jsonMessageConverter(JSON);

    @Test
    void readsTheProducersFixture() throws IOException {
        ClaimSubmittedEvent event = convert(fixture());

        assertThat(event.eventId()).isEqualTo(UUID.fromString("6f1c2c1e-6a43-4c1e-9a0b-6e7d3f6c2a10"));
        assertThat(event.eventType()).isEqualTo("ClaimSubmitted");
        assertThat(event.eventVersion()).isEqualTo(1);
        ClaimSubmittedEvent.Data data = event.data();
        assertThat(data.claimNumber()).isEqualTo("HC-2026-000123");
        assertThat(data.policyNumber()).isEqualTo("RRM-12345678");
        assertThat(data.policyholder().firstName()).isEqualTo("Jane");
        assertThat(data.policyholder().email()).isEqualTo("jane.doe@example.com");
        assertThat(data.lossType()).isEqualTo("WATER");
        assertThat(data.lossDate()).isEqualTo(LocalDate.of(2026, 10, 12));
    }

    @Test
    void ignoresFieldsItDoesNotKnow() throws IOException {
        // An additive change within v1: new fields at every level
        ObjectNode body = (ObjectNode) JSON.readTree(fixture());
        body.put("source", "web");
        ObjectNode data = (ObjectNode) body.get("data");
        data.put("channel", "online");
        ((ObjectNode) data.get("policyholder")).put("preferredLanguage", "fr");

        ClaimSubmittedEvent event = convert(JSON.writeValueAsString(body));

        assertThat(event.data().claimNumber()).isEqualTo("HC-2026-000123");
        assertThat(event.data().policyholder().firstName()).isEqualTo("Jane");
    }

    private ClaimSubmittedEvent convert(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setInferredArgumentType(ClaimSubmittedEvent.class);
        return (ClaimSubmittedEvent) converter.fromMessage(new Message(body.getBytes(UTF_8), properties));
    }

    private static String fixture() throws IOException {
        return new ClassPathResource("contracts/claim-submitted-v1.json").getContentAsString(UTF_8);
    }
}
