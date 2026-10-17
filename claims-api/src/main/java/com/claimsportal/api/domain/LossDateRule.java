package com.claimsportal.api.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * A loss can't be in the future and can't be more than {@code maxAgeDays} days old.
 *
 * <p>"Today" is the calendar date of the submission in the business time zone
 * (America/Winnipeg), not in UTC. At 9 pm in Winnipeg it is already the next day in UTC,
 * and a customer reporting damage from earlier that evening must not be rejected.
 */
public final class LossDateRule {

    public static final String FIELD = "loss.date";

    private final ZoneId zone;
    private final int maxAgeDays;

    public LossDateRule(ZoneId zone, int maxAgeDays) {
        this.zone = Objects.requireNonNull(zone, "zone");
        if (maxAgeDays < 0) {
            throw new IllegalArgumentException("maxAgeDays must not be negative");
        }
        this.maxAgeDays = maxAgeDays;
    }

    public void check(LocalDate lossDate, Instant submittedAt) {
        LocalDate today = LocalDate.ofInstant(submittedAt, zone);
        if (lossDate.isAfter(today)) {
            throw new InvalidClaimException(FIELD, "The date of loss can't be in the future");
        }
        if (lossDate.isBefore(today.minusDays(maxAgeDays))) {
            throw new InvalidClaimException(FIELD,
                    "The date of loss can't be more than %d days ago".formatted(maxAgeDays));
        }
    }
}
