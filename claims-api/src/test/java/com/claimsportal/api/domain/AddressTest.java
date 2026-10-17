package com.claimsportal.api.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AddressTest {

    @ParameterizedTest
    @ValueSource(strings = {"R3C 1A1", "r3c 1a1", "R3C1A1", "r3c1a1", " R3C 1A1 ", "R3C-1A1"})
    void normalizesPostalCodeToCanadaPostFormat(String input) {
        Address address = new Address("123 Main St", null, "Winnipeg", Province.MB, input);

        assertThat(address.postalCode()).isEqualTo("R3C 1A1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12345", "R3C 1A", "R3C 1A1X", "RRC 1A1"})
    void rejectsSomethingThatIsNotAPostalCode(String input) {
        assertThatThrownBy(() -> new Address("123 Main St", null, "Winnipeg", Province.MB, input))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void treatsABlankUnitAsNoUnit(String unit) {
        Address address = new Address("123 Main St", unit, "Winnipeg", Province.MB, "R3C 1A1");

        assertThat(address.unit()).isNull();
    }

    @Test
    void trimsTextFields() {
        Address address = new Address("  123 Main St  ", " 4B ", " Winnipeg ", Province.MB, "R3C 1A1");

        assertThat(address.street()).isEqualTo("123 Main St");
        assertThat(address.unit()).isEqualTo("4B");
        assertThat(address.city()).isEqualTo("Winnipeg");
    }
}
