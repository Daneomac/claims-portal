package com.claimsportal.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.claimsportal.notification.application.ConfirmationEmail;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A careless {@code log.info("{}", event)} must not write customer details to the logs
 * (PLAN section 8), so every object that holds them overrides {@code toString()}. (The
 * rendered email has its own check in {@code ConfirmationEmailRendererTest}.)
 */
class PersonalInformationToStringTest {

    private static final String FIRST_NAME = "Jeanette";
    private static final String EMAIL = "jeanette.thibodeaux@example.com";
    private static final String POLICY_NUMBER = "RRM-87654321";

    static Stream<Object> objectsHoldingPersonalInformation() {
        var policyholder = new ClaimSubmittedEvent.Policyholder(FIRST_NAME, EMAIL);
        var data = new ClaimSubmittedEvent.Data("HC-2026-000123", POLICY_NUMBER, policyholder, "WATER",
                LocalDate.of(2026, 10, 12));
        var event = new ClaimSubmittedEvent(UUID.fromString("6f1c2c1e-6a43-4c1e-9a0b-6e7d3f6c2a10"),
                "ClaimSubmitted", 1, data);
        var email = ConfirmationEmail.forClaim(EMAIL, FIRST_NAME, "HC-2026-000123", POLICY_NUMBER, "WATER",
                LocalDate.of(2026, 10, 12));
        return Stream.of(event, data, policyholder, email);
    }

    @ParameterizedTest
    @MethodSource("objectsHoldingPersonalInformation")
    void toStringLeavesOutPersonalInformation(Object holder) {
        assertThat(holder.toString()).doesNotContain(FIRST_NAME, EMAIL, POLICY_NUMBER, "4321");
    }

    @ParameterizedTest
    @MethodSource("objectsHoldingPersonalInformation")
    void toStringKeepsTheClaimNumberForTracing(Object holder) {
        if (!(holder instanceof ClaimSubmittedEvent.Policyholder)) {
            assertThat(holder.toString()).contains("HC-2026-000123");
        }
    }
}
