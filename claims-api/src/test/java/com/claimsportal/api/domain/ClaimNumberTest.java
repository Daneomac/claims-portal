package com.claimsportal.api.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ClaimNumberTest {

    @Test
    void padsTheSequenceToSixDigits() {
        assertThat(ClaimNumber.of(2026, 123).value()).isEqualTo("HC-2026-000123");
    }

    @Test
    void growsBeyondSixDigitsWhenNeeded() {
        assertThat(ClaimNumber.of(2026, 1_234_567).value()).isEqualTo("HC-2026-1234567");
    }

    @Test
    void rejectsANonPositiveSequence() {
        assertThatThrownBy(() -> ClaimNumber.of(2026, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAMalformedValue() {
        assertThatThrownBy(() -> new ClaimNumber("HC-26-123")).isInstanceOf(IllegalArgumentException.class);
    }
}
