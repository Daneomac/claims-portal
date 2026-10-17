package com.claimsportal.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.Customization;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.RegularExpressionValueMatcher;
import org.skyscreamer.jsonassert.comparator.CustomComparator;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The outbox relay against a real Postgres and RabbitMQ (Testcontainers), with the relay
 * running on its normal schedule. Each test binds its own queue to {@code claims.events},
 * standing in for the notification service's queue (which that service declares itself).
 *
 * <p>The message body is compared with {@code contracts/claim-submitted-v1.json}: this is the
 * producer side of the event contract test (ADR 0005). The notification service's tests parse
 * a copy of the same file, so a change that breaks consumers fails here first.
 *
 * <p>The trace propagation through the outbox is checked here too. Spring Boot tests switch
 * tracing export off by default, and with it W3C propagation (no traceparent is read or
 * written), so {@code @AutoConfigureTracing} switches it back on. No OTLP endpoint is set, so
 * the spans still aren't sent anywhere.
 */
@SpringBootTest(properties = "claims.outbox.confirm-timeout=1s")
@AutoConfigureMockMvc
@AutoConfigureTracing
@Import(TestcontainersConfiguration.class)
class OutboxPublishingIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String INSTANT_PATTERN = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z";

    /**
     * Strict: no missing or extra fields, and every value equal to the fixture's, except the
     * values that differ per claim, which only have to have the right format.
     */
    private static final CustomComparator CONTRACT = new CustomComparator(JSONCompareMode.STRICT,
            new Customization("eventId", new RegularExpressionValueMatcher<>(UUID_PATTERN)),
            new Customization("occurredAt", new RegularExpressionValueMatcher<>(INSTANT_PATTERN)),
            new Customization("data.claimNumber", new RegularExpressionValueMatcher<>("HC-\\d{4}-\\d{6,}")),
            new Customization("data.lossDate", new RegularExpressionValueMatcher<>("\\d{4}-\\d{2}-\\d{2}")),
            new Customization("data.submittedAt", new RegularExpressionValueMatcher<>(INSTANT_PATTERN)));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitMQContainer rabbitContainer;

    @Autowired
    private MeterRegistry meterRegistry;

    private String testQueue;

    @AfterEach
    void deleteTestQueue() {
        if (testQueue != null) {
            amqpAdmin.deleteQueue(testQueue);
        }
    }

    @Test
    void publishesASubmittedClaimToTheClaimsEventsExchange() throws Exception {
        bindTestQueue();
        LocalDate lossDate = LocalDate.now(ZoneId.of("America/Winnipeg")).minusDays(5);

        String claimNumber = submitClaim(lossDate);
        Message message = awaitMessageFor(claimNumber);

        String body = new String(message.getBody(), UTF_8);
        String eventId = jsonMapper.readTree(body).get("eventId").asString();
        MessageProperties properties = message.getMessageProperties();
        assertThat(properties.getMessageId()).isEqualTo(eventId);
        assertThat(properties.getType()).isEqualTo("ClaimSubmitted");
        assertThat(properties.getContentType()).isEqualTo("application/json");
        assertThat(properties.getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(properties.<Integer>getHeader("eventVersion")).isEqualTo(1);
        assertThat(properties.getReceivedExchange()).isEqualTo("claims.events");
        assertThat(properties.getReceivedRoutingKey()).isEqualTo("claim.submitted");

        JSONAssert.assertEquals(contractFixture(), body, CONTRACT);
        assertThat(jsonMapper.readTree(body).at("/data/lossDate").asString()).isEqualTo(lossDate.toString());

        // The row is marked published once the broker has confirmed the message
        await().atMost(TIMEOUT).untilAsserted(() -> {
            Map<String, Object> row = outboxRow(claimNumber);
            assertThat(row.get("id")).hasToString(eventId);
            assertThat(row.get("published_at")).isNotNull();
            assertThat(row).containsEntry("attempts", 0);
        });
    }

    @Test
    void keepsTheEventPendingWhileNothingIsBoundToTheExchange() throws Exception {
        // No queue is bound yet, so the broker returns the message (mandatory flag)
        String claimNumber = submitClaim(LocalDate.now(ZoneId.of("America/Winnipeg")).minusDays(1));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            Map<String, Object> row = outboxRow(claimNumber);
            assertThat(row.get("published_at")).isNull();
            assertThat((Integer) row.get("attempts")).isPositive();
            assertThat(row).containsEntry("last_error", "The broker returned the message as unroutable: 312 NO_ROUTE");
        });

        bindTestQueue();

        awaitMessageFor(claimNumber);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(outboxRow(claimNumber).get("published_at")).isNotNull());
    }

    @Test
    void acceptsClaimsWhileTheBrokerIsDownAndPublishesThemOnceItIsBack() throws Exception {
        bindTestQueue();
        String claimNumber;

        // A paused container keeps its connections open but never answers, so the publish
        // gets no confirm and times out, as it would with a hung or partitioned broker
        pauseBroker();
        try {
            claimNumber = submitClaim(LocalDate.now(ZoneId.of("America/Winnipeg")).minusDays(2)); // still 201

            await().atMost(TIMEOUT).untilAsserted(() -> {
                Map<String, Object> row = outboxRow(claimNumber);
                assertThat(row.get("published_at")).isNull();
                assertThat((Integer) row.get("attempts")).isPositive();
                assertThat(row.get("last_error")).isNotNull();
            });
            assertThat(meterRegistry.get("outbox.pending").gauge().value()).isGreaterThanOrEqualTo(1);
        } finally {
            unpauseBroker();
        }

        // At-least-once: a publish that timed out may still arrive, so this can be a duplicate
        awaitMessageFor(claimNumber);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(outboxRow(claimNumber).get("published_at")).isNotNull());
    }

    /**
     * PLAN section 9: one trace from the HTTP request, through the outbox row and the relay, to
     * the message. The trace ID comes back in the X-Trace-Id header; the event's saved
     * traceparent and the message's traceparent header must both be in that trace.
     */
    @Test
    void theMessageContinuesTheTraceOfTheRequestThatSubmittedTheClaim() throws Exception {
        bindTestQueue();

        MockHttpServletResponse response = postClaim(LocalDate.now(ZoneId.of("America/Winnipeg")).minusDays(3), null);
        String traceId = response.getHeader("X-Trace-Id");
        String claimNumber = jsonMapper.readTree(response.getContentAsString()).get("claimNumber").asString();

        assertThat(traceId).matches("[0-9a-f]{32}");
        assertThat((String) outboxRow(claimNumber).get("trace_parent")).matches(traceParentIn(traceId));
        Message message = awaitMessageFor(claimNumber);
        assertThat(message.getMessageProperties().<String>getHeader("traceparent")).matches(traceParentIn(traceId));
    }

    /** A caller that is itself traced (e.g. a gateway) sends a traceparent; the claim joins that trace. */
    @Test
    void joinsATraceStartedByTheCaller() throws Exception {
        String callersTraceId = UUID.randomUUID().toString().replace("-", "");

        MockHttpServletResponse response = postClaim(LocalDate.now(ZoneId.of("America/Winnipeg")).minusDays(3),
                "00-" + callersTraceId + "-00f067aa0ba902b7-01");
        String claimNumber = jsonMapper.readTree(response.getContentAsString()).get("claimNumber").asString();

        assertThat(response.getHeader("X-Trace-Id")).isEqualTo(callersTraceId);
        assertThat((String) outboxRow(claimNumber).get("trace_parent")).matches(traceParentIn(callersTraceId));
    }

    /** E.g. a row written before tracing was added: it still publishes, in a trace of its own. */
    @Test
    void publishesAnEventSavedWithoutTraceContextInANewTrace() {
        bindTestQueue();
        String claimNumber = "HC-2026-" + (900_000 + new Random().nextInt(100_000));
        jdbcClient.sql("""
                        INSERT INTO outbox_event (id, aggregate_id, event_type, event_version, payload, created_at)
                        VALUES (?, ?, 'ClaimSubmitted', 1, ?::jsonb, now())
                        """)
                .params(UUID.randomUUID(), UUID.randomUUID(), "{\"data\": {\"claimNumber\": \"" + claimNumber + "\"}}")
                .update();

        Message message = awaitMessageFor(claimNumber);

        assertThat(message.getMessageProperties().<String>getHeader("traceparent"))
                .matches(traceParentIn("[0-9a-f]{32}"));
    }

    /**
     * A W3C traceparent in the given trace: version 00, trace ID, span ID, then the flags,
     * which vary (01 = sampled; OpenTelemetry also sets 02 for a trace ID it generated).
     */
    private static String traceParentIn(String traceId) {
        return "00-" + traceId + "-[0-9a-f]{16}-[0-9a-f]{2}";
    }

    private void bindTestQueue() {
        // Durable and not exclusive (RabbitMQ 4 no longer allows transient shared queues), so it
        // also survives the broker-down test; deleted after each test
        Queue queue = QueueBuilder.durable("claims-api-test." + UUID.randomUUID()).build();
        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange("claims.events")).with("claim.submitted"));
        testQueue = queue.getName();
    }

    /** Waits for this claim's event, skipping messages for claims from other tests. */
    private Message awaitMessageFor(String claimNumber) {
        return await().atMost(TIMEOUT).until(() -> {
            Message message = rabbitTemplate.receive(testQueue);
            boolean forThisClaim = message != null && claimNumber.equals(
                    jsonMapper.readTree(message.getBody()).at("/data/claimNumber").asString());
            return forThisClaim ? message : null;
        }, Objects::nonNull);
    }

    private String submitClaim(LocalDate lossDate) throws Exception {
        String response = postClaim(lossDate, null).getContentAsString();
        return jsonMapper.readTree(response).get("claimNumber").asString();
    }

    /** Submits a claim, sending a traceparent header if one is given, and expects 201. */
    private MockHttpServletResponse postClaim(LocalDate lossDate, String traceParent) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/claims")
                .contentType(MediaType.APPLICATION_JSON)
                .content(claimJson(lossDate));
        if (traceParent != null) {
            request.header("traceparent", traceParent);
        }
        return mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andReturn().getResponse();
    }

    private Map<String, Object> outboxRow(String claimNumber) {
        return jdbcClient.sql("""
                        SELECT o.* FROM outbox_event o
                        JOIN claim c ON c.id = o.aggregate_id
                        WHERE c.claim_number = ?
                        """)
                .param(claimNumber)
                .query().singleRow();
    }

    private void pauseBroker() {
        rabbitContainer.getDockerClient().pauseContainerCmd(rabbitContainer.getContainerId()).exec();
    }

    private void unpauseBroker() {
        rabbitContainer.getDockerClient().unpauseContainerCmd(rabbitContainer.getContainerId()).exec();
    }

    private static String contractFixture() throws Exception {
        return new ClassPathResource("contracts/claim-submitted-v1.json").getContentAsString(UTF_8);
    }

    /** The same customer details as the contract fixture, so the fixed fields match it. */
    private static String claimJson(LocalDate lossDate) {
        return """
                {
                  "policyholder": { "firstName": "Jane", "lastName": "Doe",
                                    "email": "jane.doe@example.com", "phone": "204-555-0142" },
                  "policyNumber": "RRM-12345678",
                  "loss": { "date": "%s", "type": "WATER",
                            "description": "Burst pipe in the basement flooded the finished rec room.",
                            "estimatedAmount": 8500.00 },
                  "propertyAddress": { "street": "123 Main St", "unit": null, "city": "Winnipeg",
                                       "province": "MB", "postalCode": "R3C 1A1" },
                  "declarationAccepted": true
                }
                """.formatted(lossDate);
    }
}
