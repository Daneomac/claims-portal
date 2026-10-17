package com.claimsportal.api.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A home insurance claim. New claims are only created through {@link #submit}, which
 * applies the business rules, so an invalid claim can't be constructed.
 */
public final class Claim {

    private static final Pattern POLICY_NUMBER = Pattern.compile("RRM-\\d{8}");

    private final UUID id;
    private final ClaimNumber claimNumber;
    private final String policyNumber;
    private final Policyholder policyholder;
    private final Loss loss;
    private final Address propertyAddress;
    private final ClaimStatus status;
    private final Instant submittedAt;

    private Claim(UUID id, ClaimNumber claimNumber, String policyNumber, Policyholder policyholder,
            Loss loss, Address propertyAddress, ClaimStatus status, Instant submittedAt) {
        this.id = id;
        this.claimNumber = claimNumber;
        this.policyNumber = policyNumber;
        this.policyholder = policyholder;
        this.loss = loss;
        this.propertyAddress = propertyAddress;
        this.status = status;
        this.submittedAt = submittedAt;
    }

    public static Claim submit(ClaimNumber claimNumber, String policyNumber, Policyholder policyholder,
            Loss loss, Address propertyAddress, Instant submittedAt, LossDateRule lossDateRule) {
        Objects.requireNonNull(claimNumber, "claimNumber");
        Objects.requireNonNull(policyholder, "policyholder");
        Objects.requireNonNull(loss, "loss");
        Objects.requireNonNull(propertyAddress, "propertyAddress");
        Objects.requireNonNull(submittedAt, "submittedAt");

        lossDateRule.check(loss.date(), submittedAt);

        return new Claim(UUID.randomUUID(), claimNumber, normalizePolicyNumber(policyNumber), policyholder,
                loss, propertyAddress, ClaimStatus.RECEIVED, submittedAt);
    }

    /** "rrm-12345678 " and "RRM-12345678" are the same policy. */
    private static String normalizePolicyNumber(String raw) {
        String normalized = Objects.requireNonNull(raw, "policyNumber").strip().toUpperCase(Locale.ROOT);
        if (!POLICY_NUMBER.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Not a policy number");
        }
        return normalized;
    }

    public UUID id() {
        return id;
    }

    public ClaimNumber claimNumber() {
        return claimNumber;
    }

    public String policyNumber() {
        return policyNumber;
    }

    public Policyholder policyholder() {
        return policyholder;
    }

    public Loss loss() {
        return loss;
    }

    public Address propertyAddress() {
        return propertyAddress;
    }

    public ClaimStatus status() {
        return status;
    }

    public Instant submittedAt() {
        return submittedAt;
    }

    @Override
    public String toString() {
        return "Claim[" + claimNumber + "]";
    }
}
