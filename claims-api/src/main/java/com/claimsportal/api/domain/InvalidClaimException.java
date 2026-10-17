package com.claimsportal.api.domain;

/**
 * A claim broke a business rule. {@code field} uses the same dotted path as the API
 * request (for example {@code loss.date}) so the error can be shown next to the input.
 */
public class InvalidClaimException extends RuntimeException {

    private final String field;

    public InvalidClaimException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
