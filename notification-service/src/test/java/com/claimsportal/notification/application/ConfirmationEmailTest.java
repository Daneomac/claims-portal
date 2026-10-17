package com.claimsportal.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConfirmationEmailTest {

    @ParameterizedTest
    @CsvSource({
            "RRM-12345678, RRM-****5678",
            "RRM-123456789, RRM-****6789",
            "RRM-12345, RRM-****2345",
            "RRM-1234, RRM-****",   // nothing left to hide behind, so show none of it
            "12345678, ****5678",   // no prefix
    })
    void masksAllButTheLastFourDigitsOfThePolicyNumber(String policyNumber, String masked) {
        assertThat(ConfirmationEmail.maskPolicyNumber(policyNumber)).isEqualTo(masked);
    }

    @Test
    void forClaimStoresOnlyTheMaskedPolicyNumber() {
        ConfirmationEmail email = ConfirmationEmail.forClaim("jane.doe@example.com", "Jane", "HC-2026-000123",
                "RRM-12345678", "WATER", LocalDate.of(2026, 10, 12));

        assertThat(email.maskedPolicyNumber()).isEqualTo("RRM-****5678");
    }
}
