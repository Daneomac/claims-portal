package com.claimsportal.api.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The address of the damaged property. The postal code is normalized to the Canada Post
 * format {@code A1A 1A1}, so "r3c1a1" and "R3C 1A1" are stored the same way.
 */
public record Address(String street, String unit, String city, Province province, String postalCode) {

    private static final Pattern POSTAL_CODE = Pattern.compile("[A-Z]\\d[A-Z] \\d[A-Z]\\d");

    public Address {
        street = Objects.requireNonNull(street, "street").strip();
        unit = unit == null || unit.isBlank() ? null : unit.strip();
        city = Objects.requireNonNull(city, "city").strip();
        Objects.requireNonNull(province, "province");
        postalCode = normalizePostalCode(Objects.requireNonNull(postalCode, "postalCode"));
    }

    private static String normalizePostalCode(String raw) {
        String compact = raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        String formatted = compact.length() == 6 ? compact.substring(0, 3) + " " + compact.substring(3) : compact;
        if (!POSTAL_CODE.matcher(formatted).matches()) {
            throw new IllegalArgumentException("Not a Canadian postal code");
        }
        return formatted;
    }
}
