package com.claimsportal.api.infrastructure;

import com.claimsportal.api.domain.ClaimSubmitted;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The JSON contract of {@code ClaimSubmitted} v1, exactly as consumers see it (PLAN section 5,
 * docs/events.md). Separate from the domain event so the wire format only changes on purpose.
 */
record ClaimSubmittedMessage(UUID eventId, String eventType, int eventVersion, Instant occurredAt, Data data) {

    record Data(String claimNumber, String policyNumber, Policyholder policyholder, String lossType,
            LocalDate lossDate, Instant submittedAt) {
    }

    record Policyholder(String firstName, String lastName, String email) {

        /** Personal information must never reach a log. */
        @Override
        public String toString() {
            return "Policyholder[redacted]";
        }
    }

    static ClaimSubmittedMessage from(ClaimSubmitted event) {
        return new ClaimSubmittedMessage(
                event.eventId(),
                ClaimSubmitted.TYPE,
                ClaimSubmitted.VERSION,
                event.occurredAt(),
                new Data(
                        event.claimNumber().value(),
                        event.policyNumber(),
                        new Policyholder(event.firstName(), event.lastName(), event.email()),
                        event.lossType().name(),
                        event.lossDate(),
                        event.submittedAt()));
    }
}
