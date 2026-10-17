package com.claimsportal.api.application;

import com.claimsportal.api.domain.Claim;
import com.claimsportal.api.domain.ClaimNumber;
import com.claimsportal.api.domain.ClaimStatus;
import java.time.Instant;

/** What the customer is told once their claim is accepted. */
public record SubmittedClaim(ClaimNumber claimNumber, ClaimStatus status, Instant submittedAt) {

    static SubmittedClaim from(Claim claim) {
        return new SubmittedClaim(claim.claimNumber(), claim.status(), claim.submittedAt());
    }
}
