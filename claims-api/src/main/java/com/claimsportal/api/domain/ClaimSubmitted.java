package com.claimsportal.api.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Raised when a claim has been accepted. It carries only what the confirmation email needs:
 * no damage description, phone number or address (see PLAN section 5).
 */
public record ClaimSubmitted(
        UUID eventId,
        Instant occurredAt,
        UUID claimId,
        ClaimNumber claimNumber,
        String policyNumber,
        String firstName,
        String lastName,
        String email,
        LossType lossType,
        LocalDate lossDate,
        Instant submittedAt) {

    public static final String TYPE = "ClaimSubmitted";
    public static final int VERSION = 1;

    public static ClaimSubmitted from(Claim claim, Instant occurredAt) {
        Policyholder policyholder = claim.policyholder();
        return new ClaimSubmitted(
                UUID.randomUUID(),
                occurredAt,
                claim.id(),
                claim.claimNumber(),
                claim.policyNumber(),
                policyholder.firstName(),
                policyholder.lastName(),
                policyholder.email(),
                claim.loss().type(),
                claim.loss().date(),
                claim.submittedAt());
    }

    /** Personal information must never reach a log, so only identifiers are printed. */
    @Override
    public String toString() {
        return "ClaimSubmitted[eventId=" + eventId + ", claimNumber=" + claimNumber + "]";
    }
}
