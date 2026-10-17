package com.claimsportal.api.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-17T17:00:00Z");
    private static final int BATCH_SIZE = 50;

    @Mock
    private OutboxEventRepository outbox;

    @Mock
    private EventPublisher publisher;

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        var properties = new OutboxProperties(true, Duration.ofMillis(500), BATCH_SIZE, Duration.ofSeconds(5));
        relay = new OutboxRelay(outbox, publisher, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void marksConfirmedEventsAsPublished() {
        OutboxEventEntity first = pendingEvent();
        OutboxEventEntity second = pendingEvent();
        given(outbox.lockNextBatch(BATCH_SIZE)).willReturn(List.of(first, second));

        int published = relay.publishPendingEvents();

        assertThat(published).isEqualTo(2);
        verify(publisher).publish(first.toMessage());
        verify(publisher).publish(second.toMessage());
        assertThat(first.publishedAt()).isEqualTo(NOW);
        assertThat(second.publishedAt()).isEqualTo(NOW);
        assertThat(first.attempts()).isZero();
    }

    @Test
    void leavesAFailedEventPendingAndStopsTheBatch() {
        OutboxEventEntity confirmed = pendingEvent();
        OutboxEventEntity failed = pendingEvent();
        OutboxEventEntity notTried = pendingEvent();
        given(outbox.lockNextBatch(BATCH_SIZE)).willReturn(List.of(confirmed, failed, notTried));
        willDoNothing().given(publisher).publish(confirmed.toMessage());
        willThrow(new EventPublishException("No confirm from the broker within PT5S"))
                .given(publisher).publish(failed.toMessage());

        int published = relay.publishPendingEvents();

        assertThat(published).isEqualTo(1);
        assertThat(confirmed.publishedAt()).isEqualTo(NOW);

        assertThat(failed.publishedAt()).isNull();
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.lastError()).isEqualTo("No confirm from the broker within PT5S");

        verify(publisher, never()).publish(notTried.toMessage());
        assertThat(notTried.publishedAt()).isNull();
        assertThat(notTried.attempts()).isZero();
    }

    @Test
    void countsEachFailedAttempt() {
        OutboxEventEntity event = pendingEvent();
        given(outbox.lockNextBatch(BATCH_SIZE)).willReturn(List.of(event));
        willThrow(new EventPublishException("first failure"), new EventPublishException("second failure"))
                .given(publisher).publish(any());

        relay.publishPendingEvents();
        relay.publishPendingEvents();

        assertThat(event.attempts()).isEqualTo(2);
        assertThat(event.lastError()).isEqualTo("second failure");
        assertThat(event.publishedAt()).isNull();
    }

    @Test
    void doesNothingWhenNoEventsArePending() {
        given(outbox.lockNextBatch(BATCH_SIZE)).willReturn(List.of());

        assertThat(relay.publishPendingEvents()).isZero();
        verify(publisher, never()).publish(any());
    }

    @Test
    void truncatesALongErrorToFitTheColumn() {
        OutboxEventEntity event = pendingEvent();

        event.recordFailure("x".repeat(OutboxEventEntity.MAX_ERROR_LENGTH + 50));

        assertThat(event.lastError()).hasSize(OutboxEventEntity.MAX_ERROR_LENGTH);
    }

    private static OutboxEventEntity pendingEvent() {
        UUID eventId = UUID.randomUUID();
        return OutboxEventEntity.pending(eventId, UUID.randomUUID(), "ClaimSubmitted", 1,
                "{\"eventId\":\"" + eventId + "\"}", null, NOW.minusSeconds(1));
    }
}
