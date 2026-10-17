package com.claimsportal.notification.application;

import java.time.LocalDate;

/**
 * What goes into the "we've received your claim" email. Only the details the customer needs
 * to recognise their claim; the damage description is deliberately not part of it.
 *
 * <p>Create it with {@link #forClaim}, which masks the policy number, so the full number never
 * reaches the email.
 *
 * @param recipient          the policyholder's email address
 * @param firstName          for the greeting
 * @param claimNumber        e.g. {@code HC-2026-000123}
 * @param maskedPolicyNumber e.g. {@code RRM-****5678}
 * @param lossType           the contract's code, e.g. {@code WATER}
 * @param lossDate           the date the loss happened
 */
public record ConfirmationEmail(String recipient, String firstName, String claimNumber, String maskedPolicyNumber,
        String lossType, LocalDate lossDate) {

    private static final String MASK = "****";
    private static final int VISIBLE_DIGITS = 4;

    public static ConfirmationEmail forClaim(String recipient, String firstName, String claimNumber,
            String policyNumber, String lossType, LocalDate lossDate) {
        return new ConfirmationEmail(recipient, firstName, claimNumber, maskPolicyNumber(policyNumber), lossType,
                lossDate);
    }

    /**
     * Keeps the prefix and the last four digits: {@code RRM-12345678} becomes
     * {@code RRM-****5678}. That's enough for the customer to recognise their policy, and not
     * enough to be useful to anyone who reads the email. A number with four digits or fewer
     * after the prefix is masked completely.
     */
    static String maskPolicyNumber(String policyNumber) {
        int digitsStart = policyNumber.indexOf('-') + 1; // 0 when there's no prefix
        String prefix = policyNumber.substring(0, digitsStart);
        String digits = policyNumber.substring(digitsStart);
        String visible = digits.length() > VISIBLE_DIGITS ? digits.substring(digits.length() - VISIBLE_DIGITS) : "";
        return prefix + MASK + visible;
    }

    /** Personal information must never reach a log. */
    @Override
    public String toString() {
        return "ConfirmationEmail[claimNumber=" + claimNumber + ", recipient and name redacted]";
    }
}
