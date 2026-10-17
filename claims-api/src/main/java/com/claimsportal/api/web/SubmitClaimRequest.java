package com.claimsportal.api.web;

import com.claimsportal.api.application.SubmitClaimCommand;
import com.claimsportal.api.domain.Address;
import com.claimsportal.api.domain.Loss;
import com.claimsportal.api.domain.LossType;
import com.claimsportal.api.domain.Policyholder;
import com.claimsportal.api.domain.Province;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The JSON body of {@code POST /api/v1/claims}. The annotations check the shape of every
 * field (PLAN section 4, validation rules); business rules such as the loss date window
 * are checked by the domain.
 */
public record SubmitClaimRequest(
        @NotNull(message = "Enter your details")
        @Valid
        PolicyholderDetails policyholder,

        @NotBlank(message = "Enter your policy number")
        @Pattern(regexp = POLICY_NUMBER, message = "Enter a policy number like RRM-12345678")
        String policyNumber,

        @NotNull(message = "Tell us what happened")
        @Valid
        LossDetails loss,

        @NotNull(message = "Enter the property address")
        @Valid
        AddressDetails propertyAddress,

        // Boolean, not boolean: Jackson 3 rejects a missing primitive as malformed JSON,
        // and a missing declaration should be reported like any other required field
        @NotNull(message = DECLARATION)
        @AssertTrue(message = DECLARATION)
        Boolean declarationAccepted) {

    static final String DECLARATION = "Confirm that the information you've provided is accurate";

    /** Letters in any language, spaces, apostrophes, hyphens and periods. */
    static final String NAME = "[\\p{L}\\p{M} '.-]+";
    /** North American numbers, e.g. 204-555-0142, (204) 555-0142, +1 204 555 0142. */
    static final String PHONE = "(\\+?1[ .-]?)?(\\([2-9]\\d{2}\\)|[2-9]\\d{2})[ .-]?[2-9]\\d{2}[ .-]?\\d{4}";
    /** Case-insensitive; normalized to upper case by the domain. */
    static final String POLICY_NUMBER = "(?i)\\s*RRM-\\d{8}\\s*";
    /** Canada Post format; the letters D, F, I, O, Q and U are never used. */
    static final String POSTAL_CODE =
            "(?i)\\s*[ABCEGHJ-NPRSTVXY]\\d[ABCEGHJ-NPRSTV-Z][ -]?\\d[ABCEGHJ-NPRSTV-Z]\\d\\s*";

    public record PolicyholderDetails(
            @NotBlank(message = "Enter your first name")
            @Size(max = 100, message = "First name must be 100 characters or fewer")
            @Pattern(regexp = NAME, message = "First name can only contain letters, spaces, apostrophes, hyphens and periods")
            String firstName,

            @NotBlank(message = "Enter your last name")
            @Size(max = 100, message = "Last name must be 100 characters or fewer")
            @Pattern(regexp = NAME, message = "Last name can only contain letters, spaces, apostrophes, hyphens and periods")
            String lastName,

            @NotBlank(message = "Enter your email address")
            @Size(max = 254, message = "Email address must be 254 characters or fewer")
            @Email(regexp = ".+@.+\\..+", message = "Enter a valid email address, like name@example.com")
            String email,

            @Pattern(regexp = PHONE, message = "Enter a phone number like 204-555-0142")
            String phone) {

        @Override
        public String toString() {
            return "PolicyholderDetails[redacted]";
        }
    }

    public record LossDetails(
            @NotNull(message = "Enter the date of loss")
            LocalDate date,

            @NotNull(message = "Choose the type of loss")
            LossType type,

            @NotBlank(message = "Describe what happened")
            @Size(min = 20, max = 2000, message = "Description must be between 20 and 2000 characters")
            String description,

            @DecimalMin(value = "0.00", inclusive = false, message = "Estimated amount must be more than $0")
            @DecimalMax(value = "10000000.00", message = "Estimated amount can't be more than $10,000,000")
            @Digits(integer = 8, fraction = 2, message = "Estimated amount can have at most 2 decimal places")
            BigDecimal estimatedAmount) {
    }

    public record AddressDetails(
            @NotBlank(message = "Enter the street address")
            @Size(max = 200, message = "Street address must be 200 characters or fewer")
            String street,

            @Size(max = 20, message = "Unit must be 20 characters or fewer")
            String unit,

            @NotBlank(message = "Enter the city")
            @Size(max = 100, message = "City must be 100 characters or fewer")
            String city,

            @NotNull(message = "Choose the province or territory")
            Province province,

            @NotBlank(message = "Enter the postal code")
            @Pattern(regexp = POSTAL_CODE, message = "Enter a postal code like R3C 1A1")
            String postalCode) {

        @Override
        public String toString() {
            return "AddressDetails[redacted]";
        }
    }

    SubmitClaimCommand toCommand() {
        return new SubmitClaimCommand(
                new Policyholder(policyholder.firstName(), policyholder.lastName(), policyholder.email(),
                        policyholder.phone()),
                policyNumber,
                new Loss(loss.date(), loss.type(), loss.description(), loss.estimatedAmount()),
                new Address(propertyAddress.street(), propertyAddress.unit(), propertyAddress.city(),
                        propertyAddress.province(), propertyAddress.postalCode()));
    }

    /** Personal information must never reach a log, even by accident. */
    @Override
    public String toString() {
        return "SubmitClaimRequest[redacted]";
    }
}
