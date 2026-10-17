package com.claimsportal.api.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/** A claim's public reference, for example {@code HC-2026-000123}. */
public record ClaimNumber(String value) {

    private static final Pattern FORMAT = Pattern.compile("HC-\\d{4}-\\d{6,}");

    public ClaimNumber {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Not a claim number: " + value);
        }
    }

    /** Pads the sequence to at least six digits; larger sequences simply get longer. */
    public static ClaimNumber of(int year, long sequence) {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        return new ClaimNumber("HC-%d-%06d".formatted(year, sequence));
    }

    @Override
    public String toString() {
        return value;
    }
}
