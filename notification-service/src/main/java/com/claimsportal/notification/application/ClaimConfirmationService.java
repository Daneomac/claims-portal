package com.claimsportal.notification.application;

import com.claimsportal.notification.persistence.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The "email the customer their claim number" use case, safe to run more than once per event. */
@Service
public class ClaimConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(ClaimConfirmationService.class);

    private final ProcessedEventRepository processedEvents;
    private final EmailSender emailSender;
    private final Clock clock;
    private final Counter sent;
    private final Counter duplicatesSkipped;

    public ClaimConfirmationService(ProcessedEventRepository processedEvents, EmailSender emailSender, Clock clock,
            MeterRegistry meterRegistry) {
        this.processedEvents = processedEvents;
        this.emailSender = emailSender;
        this.clock = clock;
        this.sent = Counter.builder("notifications.sent")
                .description("Confirmation emails accepted by the mail server")
                .register(meterRegistry);
        this.duplicatesSkipped = Counter.builder("notifications.duplicates_skipped")
                .description("Events skipped because they had already been handled")
                .register(meterRegistry);
    }

    /**
     * Sends the confirmation for an event, unless the event has already been handled.
     *
     * <p>Idempotency (ADR 0003) comes from recording the event ID in the same transaction as
     * the send. The insert comes first and is also the duplicate check:
     * <ul>
     *   <li><b>Why not check, then insert?</b> ({@code existsById}, then {@code save}.) Two
     *       deliveries of the same event, handled at the same time, could both see "not there
     *       yet" and both send an email. The primary key would only stop the second insert at
     *       commit, after its email had already gone.</li>
     *   <li><b>Why not insert and catch the duplicate-key exception?</b> In Postgres a failed
     *       statement aborts the whole transaction, and Spring marks it rollback-only, so the
     *       "skip" path would have to work around a broken transaction.
     *       {@code ON CONFLICT DO NOTHING} reports the duplicate as "0 rows" with no error.</li>
     *   <li><b>Concurrent duplicates:</b> if another consumer has inserted the same event ID
     *       and is still sending, this insert waits on the primary key until that transaction
     *       ends. If it committed, we skip; if it rolled back because sending failed, we insert
     *       and send instead. Either way, one email.</li>
     * </ul>
     * The row only commits after the mail server has accepted the email. If sending fails,
     * the exception rolls the transaction back, so the event isn't marked as handled, and the
     * listener's retry (ADR 0004) starts again from the insert.
     *
     * <p>The one remaining gap: if the process dies after the send but before the commit,
     * the redelivered event sends a second email. Email can't take part in a database
     * transaction, so that's the accepted at-least-once trade-off.
     */
    @Transactional
    public void sendConfirmation(UUID eventId, ConfirmationEmail email) {
        // Postgres stores microseconds; truncating keeps the stored value equal to this one
        int inserted = processedEvents.insertIfAbsent(eventId, email.claimNumber(),
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        if (inserted == 0) {
            duplicatesSkipped.increment();
            log.info("Event {} for claim {} was already handled; skipping it", eventId, email.claimNumber());
            return;
        }

        try {
            emailSender.send(email);
        } catch (RuntimeException e) {
            // Only the exception's type: an SMTP error message can quote the recipient's address
            log.warn("Could not send the confirmation for claim {} (event {}): {}", email.claimNumber(), eventId,
                    NestedExceptionUtils.getMostSpecificCause(e).getClass().getName());
            throw e;
        }

        // Counted before the commit. If the commit failed, the event is retried and may be
        // counted (and emailed) twice, which is the same at-least-once gap described above.
        sent.increment();
        log.info("Sent the confirmation for claim {} (event {})", email.claimNumber(), eventId);
    }
}
