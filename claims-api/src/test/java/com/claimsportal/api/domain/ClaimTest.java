package com.claimsportal.api.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClaimTest {

    private static final Instant SUBMITTED_AT = Instant.parse("2026-10-17T17:00:00Z");
    private static final LossDateRule RULE = new LossDateRule(ZoneId.of("America/Winnipeg"), 365);
    private static final ClaimNumber CLAIM_NUMBER = ClaimNumber.of(2026, 123);

    private final Policyholder policyholder = new Policyholder("Jane", "Doe", "jane.doe@example.com", null);
    private final Address address = new Address("123 Main St", null, "Winnipeg", Province.MB, "R3C 1A1");

    @Test
    void submittedClaimIsReceivedWithItsNumberAndTime() {
        Claim claim = submit("RRM-12345678", lossOn(LocalDate.of(2026, 10, 12)));

        assertThat(claim.id()).isNotNull();
        assertThat(claim.claimNumber()).isEqualTo(CLAIM_NUMBER);
        assertThat(claim.status()).isEqualTo(ClaimStatus.RECEIVED);
        assertThat(claim.submittedAt()).isEqualTo(SUBMITTED_AT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RRM-12345678", "rrm-12345678", "Rrm-12345678", "  RRM-12345678  "})
    void normalizesThePolicyNumberToUpperCase(String input) {
        Claim claim = submit(input, lossOn(LocalDate.of(2026, 10, 12)));

        assertThat(claim.policyNumber()).isEqualTo("RRM-12345678");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "RRM-1234567", "RRM-123456789", "ABC-12345678", "RRM12345678"})
    void rejectsSomethingThatIsNotAPolicyNumber(String input) {
        assertThatThrownBy(() -> submit(input, lossOn(LocalDate.of(2026, 10, 12))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void appliesTheLossDateRule() {
        assertThatThrownBy(() -> submit("RRM-12345678", lossOn(LocalDate.of(2026, 10, 18))))
                .isInstanceOf(InvalidClaimException.class)
                .extracting("field").isEqualTo("loss.date");
    }

    @Test
    void policyholderIsTrimmedAndEmailLowerCased() {
        Policyholder typed = new Policyholder("  Jane ", " Doe  ", "  Jane.Doe@Example.COM ", "  ");

        assertThat(typed.firstName()).isEqualTo("Jane");
        assertThat(typed.lastName()).isEqualTo("Doe");
        assertThat(typed.email()).isEqualTo("jane.doe@example.com");
        assertThat(typed.phone()).isNull();
    }

    private Claim submit(String policyNumber, Loss loss) {
        return Claim.submit(CLAIM_NUMBER, policyNumber, policyholder, loss, address, SUBMITTED_AT, RULE);
    }

    private static Loss lossOn(LocalDate date) {
        return new Loss(date, LossType.WATER, "Burst pipe in the basement flooded the rec room.",
                new BigDecimal("8500.00"));
    }
}
