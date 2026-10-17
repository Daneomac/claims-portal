package com.claimsportal.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.claimsportal.api.application.SubmitClaimCommand;
import com.claimsportal.api.domain.Address;
import com.claimsportal.api.domain.Claim;
import com.claimsportal.api.domain.ClaimNumber;
import com.claimsportal.api.domain.ClaimSubmitted;
import com.claimsportal.api.domain.Loss;
import com.claimsportal.api.domain.LossDateRule;
import com.claimsportal.api.domain.LossType;
import com.claimsportal.api.domain.Policyholder;
import com.claimsportal.api.domain.Province;
import com.claimsportal.api.web.SubmitClaimRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A careless {@code log.info("{}", request)} must not write customer details to the logs
 * (PLAN section 8), so every object that holds them overrides {@code toString()}.
 */
class PersonalInformationToStringTest {

    private static final String FIRST_NAME = "Jeanette";
    private static final String LAST_NAME = "Thibodeaux";
    private static final String EMAIL = "jeanette.thibodeaux@example.com";
    private static final String PHONE = "204-555-0142";
    private static final String STREET = "123 Rue Provencher";

    static Stream<Object> objectsHoldingPersonalInformation() {
        var request = new SubmitClaimRequest(
                new SubmitClaimRequest.PolicyholderDetails(FIRST_NAME, LAST_NAME, EMAIL, PHONE),
                "RRM-12345678",
                new SubmitClaimRequest.LossDetails(LocalDate.of(2026, 10, 12), LossType.WATER,
                        "Burst pipe in the basement flooded the rec room.", new BigDecimal("8500.00")),
                new SubmitClaimRequest.AddressDetails(STREET, null, "Winnipeg", Province.MB, "R2H 0A1"),
                true);
        var policyholder = new Policyholder(FIRST_NAME, LAST_NAME, EMAIL, PHONE);
        var loss = new Loss(LocalDate.of(2026, 10, 12), LossType.WATER,
                "Burst pipe in the basement flooded the rec room.", null);
        var address = new Address(STREET, null, "Winnipeg", Province.MB, "R2H 0A1");
        var command = new SubmitClaimCommand(policyholder, "RRM-12345678", loss, address);
        var submittedAt = Instant.parse("2026-10-17T17:00:00Z");
        var claim = Claim.submit(ClaimNumber.of(2026, 1), "RRM-12345678", policyholder, loss, address, submittedAt,
                new LossDateRule(ZoneId.of("America/Winnipeg"), 365));

        return Stream.of(
                request,
                request.policyholder(),
                request.propertyAddress(),
                policyholder,
                command,
                claim,
                ClaimSubmitted.from(claim, submittedAt));
    }

    @ParameterizedTest
    @MethodSource("objectsHoldingPersonalInformation")
    void toStringLeavesOutPersonalInformation(Object holder) {
        assertThat(holder.toString()).doesNotContain(FIRST_NAME, LAST_NAME, EMAIL, PHONE, STREET);
    }
}
