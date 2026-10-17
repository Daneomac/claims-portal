package com.claimsportal.api.application;

import com.claimsportal.api.domain.Claim;
import com.claimsportal.api.domain.ClaimSubmitted;
import com.claimsportal.api.domain.LossDateRule;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The "submit a claim" use case. */
@Service
public class ClaimSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(ClaimSubmissionService.class);

    private final ClaimNumberGenerator claimNumberGenerator;
    private final ClaimRepository claims;
    private final EventOutbox outbox;
    private final LossDateRule lossDateRule;
    private final Clock clock;

    public ClaimSubmissionService(ClaimNumberGenerator claimNumberGenerator, ClaimRepository claims,
            EventOutbox outbox, LossDateRule lossDateRule, Clock clock) {
        this.claimNumberGenerator = claimNumberGenerator;
        this.claims = claims;
        this.outbox = outbox;
        this.lossDateRule = lossDateRule;
        this.clock = clock;
    }

    /**
     * Saves the claim and its {@code ClaimSubmitted} event in one transaction: either both
     * are stored or neither is (ADR 0002).
     */
    @Transactional
    public SubmittedClaim submit(SubmitClaimCommand command) {
        // Postgres stores microseconds, so truncate now and the response matches the database
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        Claim claim = Claim.submit(claimNumberGenerator.next(), command.policyNumber(), command.policyholder(),
                command.loss(), command.propertyAddress(), now, lossDateRule);
        claims.save(claim);
        outbox.append(ClaimSubmitted.from(claim, now));

        log.info("Claim {} submitted", claim.claimNumber());
        return SubmittedClaim.from(claim);
    }
}
