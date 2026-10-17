package com.claimsportal.api.web;

/** One entry in a 400 response's {@code errors} array, e.g. {@code loss.date}. */
public record ApiFieldError(String field, String message) {
}
