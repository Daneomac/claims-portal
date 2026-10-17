package com.claimsportal.api.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** What happened: when, what kind of damage, a description and an optional estimate. */
public record Loss(LocalDate date, LossType type, String description, BigDecimal estimatedAmount) {

    public Loss {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(type, "type");
        description = Objects.requireNonNull(description, "description").strip();
    }
}
