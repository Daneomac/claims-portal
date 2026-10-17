package com.claimsportal.api.web;

import com.claimsportal.api.application.SubmittedClaim;
import java.time.Instant;

/** The {@code 201 Created} body: the claim number the customer quotes from now on. */
public record SubmitClaimResponse(String claimNumber, String status, Instant submittedAt) {

    static SubmitClaimResponse from(SubmittedClaim submitted) {
        return new SubmitClaimResponse(submitted.claimNumber().value(), submitted.status().name(),
                submitted.submittedAt());
    }
}
