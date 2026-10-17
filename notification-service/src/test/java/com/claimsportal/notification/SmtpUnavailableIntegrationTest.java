package com.claimsportal.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.claimsportal.notification.TestEvents.TestClaim;
import com.claimsportal.notification.persistence.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.StringUtils;

/**
 * With no mail server listening, an event is retried and then dead-lettered (ADR 0004).
 * The test profile shortens the back-off to 100 ms, 200 ms, 400 ms.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class SmtpUnavailableIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private MeterRegistry meterRegistry;

    @DynamicPropertySource
    static void smtpServerThatIsDown(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", SmtpUnavailableIntegrationTest::unusedPort);
    }

    @Test
    void retriesFourTimesInAllThenDeadLettersTheEvent(CapturedOutput output) {
        TestClaim claim = TestClaim.random();
        double failedBefore = meterRegistry.get("notifications.failed").counter().count();

        rabbitTemplate.send(TestEvents.EXCHANGE, TestEvents.ROUTING_KEY, claim.message());

        Message deadLettered = await().atMost(TIMEOUT).until(() -> receiveDeadLettered(claim), Objects::nonNull);
        // RabbitMQ records why and from where the message was dead-lettered
        List<Map<String, ?>> deaths = deadLettered.getMessageProperties().getXDeathHeader();
        assertThat(deaths).hasSize(1);
        assertThat(deaths.getFirst().get("reason")).isEqualTo("rejected");
        assertThat(deaths.getFirst().get("queue")).isEqualTo("notification.claim-submitted");

        String failedAttempt = "Could not send the confirmation for claim " + claim.claimNumber();
        assertThat(StringUtils.countOccurrencesOf(output.getAll(), failedAttempt)).isEqualTo(4);
        assertThat(meterRegistry.get("notifications.failed").counter().count()).isEqualTo(failedBefore + 1);
        // Each failed attempt rolled back, so the event isn't marked as handled and can be replayed
        assertThat(processedEvents.existsById(claim.eventId())).isFalse();
        assertThat(output.getAll()).doesNotContain(claim.email(), claim.firstName());
    }

    private Message receiveDeadLettered(TestClaim claim) {
        Message message;
        while ((message = rabbitTemplate.receive(TestEvents.DEAD_LETTER_QUEUE)) != null) {
            if (claim.eventId().toString().equals(message.getMessageProperties().getMessageId())) {
                return message;
            }
        }
        return null;
    }

    /** A port nothing listens on: the OS hands out a free one, and the socket is closed again. */
    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
