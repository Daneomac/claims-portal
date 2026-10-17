package com.claimsportal.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.claimsportal.notification.persistence.ProcessedEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

/**
 * The use case with the database and the mail server replaced by mocks. The transaction
 * itself (rollback on a failed send, a concurrent duplicate) is covered by the integration
 * tests against real Postgres.
 */
class ClaimConfirmationServiceTest {

    private static final UUID EVENT_ID = UUID.fromString("6f1c2c1e-6a43-4c1e-9a0b-6e7d3f6c2a10");
    private static final Instant NOW = Instant.parse("2026-10-16T18:04:11.532123456Z");
    private static final ConfirmationEmail EMAIL = ConfirmationEmail.forClaim("jane.doe@example.com", "Jane",
            "HC-2026-000123", "RRM-12345678", "WATER", LocalDate.of(2026, 10, 12));

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final EmailSender emailSender = mock(EmailSender.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ClaimConfirmationService service = new ClaimConfirmationService(processedEvents, emailSender,
            Clock.fixed(NOW, ZoneOffset.UTC), meterRegistry);

    @Test
    void recordsTheEventAndSendsTheEmail() {
        when(processedEvents.insertIfAbsent(any(), anyString(), any())).thenReturn(1);

        service.sendConfirmation(EVENT_ID, EMAIL);

        verify(processedEvents).insertIfAbsent(EVENT_ID, "HC-2026-000123", Instant.parse("2026-10-16T18:04:11.532123Z"));
        verify(emailSender).send(EMAIL);
        assertThat(count("notifications.sent")).isEqualTo(1);
        assertThat(count("notifications.duplicates_skipped")).isZero();
    }

    @Test
    void skipsAnEventThatWasAlreadyHandled() {
        when(processedEvents.insertIfAbsent(any(), anyString(), any())).thenReturn(0);

        service.sendConfirmation(EVENT_ID, EMAIL);

        verify(emailSender, never()).send(any());
        assertThat(count("notifications.duplicates_skipped")).isEqualTo(1);
        assertThat(count("notifications.sent")).isZero();
    }

    @Test
    void letsAnSmtpFailurePropagateSoTheTransactionRollsBackAndTheMessageIsRetried() {
        when(processedEvents.insertIfAbsent(any(), anyString(), any())).thenReturn(1);
        MailSendException smtpDown = new MailSendException("Mail server connection failed");
        doThrow(smtpDown).when(emailSender).send(any());

        assertThatThrownBy(() -> service.sendConfirmation(EVENT_ID, EMAIL)).isSameAs(smtpDown);
        assertThat(count("notifications.sent")).isZero();
    }

    private double count(String counter) {
        return meterRegistry.get(counter).counter().count();
    }
}
