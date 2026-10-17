package com.claimsportal.api.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * The customer making the claim. Names are trimmed and the email is lower-cased so the
 * stored values are consistent however they were typed. The phone number is optional.
 */
public record Policyholder(String firstName, String lastName, String email, String phone) {

    public Policyholder {
        firstName = Objects.requireNonNull(firstName, "firstName").strip();
        lastName = Objects.requireNonNull(lastName, "lastName").strip();
        email = Objects.requireNonNull(email, "email").strip().toLowerCase(Locale.ROOT);
        phone = phone == null || phone.isBlank() ? null : phone.strip();
    }

    /** Personal information must never reach a log, so it is left out here. */
    @Override
    public String toString() {
        return "Policyholder[redacted]";
    }
}
