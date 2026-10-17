package com.claimsportal.notification.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/**
 * This service's view of the {@code ClaimSubmitted} v1 event (PLAN section 5, ADR 0005). It's
 * a "tolerant reader": it declares only the fields this service uses and ignores the rest, so
 * the producer can add fields (an additive change within v1) without breaking it.
 *
 * <p>The validation annotations are checked by {@link ClaimSubmittedListener}; a message that
 * fails them is dead-lettered.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record ClaimSubmittedEvent(
        @NotNull UUID eventId,
        String eventType,
        Integer eventVersion,
        @NotNull @Valid Data data) {

    static final String TYPE = "ClaimSubmitted";
    static final int SUPPORTED_VERSION = 1;

    @Override
    public String toString() {
        return "ClaimSubmittedEvent[eventId=" + eventId + ", eventType=" + eventType + ", eventVersion="
                + eventVersion + ", data=" + data + "]";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(
            @NotBlank String claimNumber,
            @NotBlank String policyNumber,
            @NotNull @Valid Policyholder policyholder,
            @NotBlank String lossType,
            @NotNull LocalDate lossDate) {

        /** Leaves out the policy number and the policyholder. */
        @Override
        public String toString() {
            return "Data[claimNumber=" + claimNumber + ", lossType=" + lossType + ", lossDate=" + lossDate
                    + ", policyNumber and policyholder redacted]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Policyholder(
            @NotBlank String firstName,
            @NotBlank @Email String email) {

        /** Personal information must never reach a log. */
        @Override
        public String toString() {
            return "Policyholder[redacted]";
        }
    }
}
