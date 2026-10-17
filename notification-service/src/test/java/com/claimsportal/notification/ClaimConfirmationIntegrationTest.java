package com.claimsportal.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.claimsportal.notification.TestEvents.TestClaim;
import com.claimsportal.notification.application.ClaimConfirmationService;
import com.claimsportal.notification.application.ConfirmationEmail;
import com.claimsportal.notification.application.EmailSender;
import com.claimsportal.notification.persistence.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/**
 * The whole consumer against real Postgres, RabbitMQ and Mailpit (Testcontainers): events are
 * published to {@code claims.events} the way claims-api publishes them, and the emails are
 * read back through Mailpit's API.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, MailpitContainerConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class ClaimConfirmationIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private Mailpit mailpit;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private ClaimConfirmationService confirmations;

    @Autowired
    private MeterRegistry meterRegistry;

    /** The real SMTP sender, wrapped so one test can hold a send open. */
    @MockitoSpyBean
    private EmailSender emailSender;

    @Test
    void emailsTheCustomerTheirClaimNumber() {
        TestClaim claim = TestClaim.random();

        publish(claim.message());

        List<JsonNode> received = await().atMost(TIMEOUT).until(() -> mailpit.messagesTo(claim.email()),
                messages -> !messages.isEmpty());
        assertThat(received).hasSize(1);
        JsonNode email = mailpit.message(received.getFirst().get("ID").asString());
        assertThat(email.get("Subject").asString()).isEqualTo("We've received your claim " + claim.claimNumber());
        assertThat(email.at("/From/Address").asString()).isEqualTo("claims-noreply@example.com");
        assertThat(email.get("Text").asString()).contains("Hi Jeanette,", claim.claimNumber(), "RRM-****5678");
        assertThat(email.get("HTML").asString()).contains(claim.claimNumber(), "RRM-****5678")
                .doesNotContain("RRM-12345678");
        assertThat(processedEvents.findById(claim.eventId())).hasValueSatisfying(
                processed -> assertThat(processed.getClaimNumber()).isEqualTo(claim.claimNumber()));
    }

    @Test
    void sendsOneEmailWhenTheSameEventArrivesTwice() {
        TestClaim claim = TestClaim.random();
        double skippedBefore = counter("notifications.duplicates_skipped");

        publish(claim.message());
        publish(claim.message());

        // Once the second delivery has been skipped, both have been fully handled
        await().atMost(TIMEOUT).until(() -> counter("notifications.duplicates_skipped") == skippedBefore + 1);
        assertThat(mailpit.messagesTo(claim.email())).hasSize(1);
    }

    /**
     * Two deliveries of the same event handled at the same moment (for example by two
     * instances of this service). The second insert waits on the primary key while the first
     * transaction is still sending, then sees the committed row and skips.
     */
    @Test
    void aConcurrentDuplicateWaitsForTheFirstDeliveryThenSkips() throws Exception {
        TestClaim claim = TestClaim.random();
        ConfirmationEmail email = ConfirmationEmail.forClaim(claim.email(), claim.firstName(), claim.claimNumber(),
                "RRM-12345678", "WATER", LocalDate.of(2026, 10, 12));
        CountDownLatch firstIsSending = new CountDownLatch(1);
        CountDownLatch finishFirstSend = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstIsSending.countDown();
            finishFirstSend.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).doCallRealMethod().when(emailSender).send(any());
        double skippedBefore = counter("notifications.duplicates_skipped");

        CompletableFuture<Void> first = CompletableFuture.runAsync(
                () -> confirmations.sendConfirmation(claim.eventId(), email));
        assertThat(firstIsSending.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> second = CompletableFuture.runAsync(
                () -> confirmations.sendConfirmation(claim.eventId(), email));

        // The second can't get past its insert while the first transaction is open
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).until(() -> !second.isDone());
        finishFirstSend.countDown();
        CompletableFuture.allOf(first, second).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        assertThat(counter("notifications.duplicates_skipped")).isEqualTo(skippedBefore + 1);
        assertThat(mailpit.messagesTo(claim.email())).hasSize(1);
    }

    @Test
    void deadLettersAnUnknownEventVersionWithoutRetrying(CapturedOutput output) {
        TestClaim claim = TestClaim.random();
        double failedBefore = counter("notifications.failed");

        publish(claim.message(2));

        Message deadLettered = awaitDeadLettered(claim.eventId().toString());
        // RabbitMQ records why and from where the message was dead-lettered
        List<Map<String, ?>> deaths = deadLettered.getMessageProperties().getXDeathHeader();
        assertThat(deaths).hasSize(1);
        assertThat(deaths.getFirst().get("reason")).isEqualTo("rejected");
        assertThat(deaths.getFirst().get("queue")).isEqualTo("notification.claim-submitted");
        assertThat(counter("notifications.failed")).isEqualTo(failedBefore + 1);
        assertThat(processedEvents.existsById(claim.eventId())).isFalse();
        assertThat(mailpit.messagesTo(claim.email())).isEmpty();
        assertThat(output.getAll())
                .contains("Unsupported event ClaimSubmitted v2 (event " + claim.eventId() + ")")
                .doesNotContain(claim.email(), claim.firstName());
    }

    @Test
    void deadLettersABodyThatIsNotJson(CapturedOutput output) {
        String messageId = TestClaim.random().eventId().toString();
        String body = "{\"policyholder\": \"jeanette@example.com\", not json";

        publish(TestEvents.amqpMessage(body, messageId, 1));

        awaitDeadLettered(messageId);
        assertThat(output.getAll())
                .contains("Giving up on event " + messageId)
                .doesNotContain("jeanette@example.com");
    }

    private void publish(Message message) {
        rabbitTemplate.send(TestEvents.EXCHANGE, TestEvents.ROUTING_KEY, message);
    }

    private Message awaitDeadLettered(String messageId) {
        return await().atMost(TIMEOUT).until(() -> receiveDeadLettered(messageId), Objects::nonNull);
    }

    /** Takes messages off the DLQ until the one with this ID turns up (or the queue is empty). */
    private Message receiveDeadLettered(String messageId) {
        Message message;
        while ((message = rabbitTemplate.receive(TestEvents.DEAD_LETTER_QUEUE)) != null) {
            if (messageId.equals(message.getMessageProperties().getMessageId())) {
                return message;
            }
        }
        return null;
    }

    private double counter(String name) {
        return meterRegistry.get(name).counter().count();
    }
}
