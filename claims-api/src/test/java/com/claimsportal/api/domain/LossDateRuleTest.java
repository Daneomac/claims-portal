package com.claimsportal.api.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class LossDateRuleTest {

    private static final ZoneId WINNIPEG = ZoneId.of("America/Winnipeg");

    // 12:00 noon in Winnipeg on 17 Oct 2026 (Winnipeg is UTC-5 in October)
    private static final Instant SUBMITTED_AT = Instant.parse("2026-10-17T17:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);

    private final LossDateRule rule = new LossDateRule(WINNIPEG, 365);

    @Test
    void acceptsToday() {
        assertThatCode(() -> rule.check(TODAY, SUBMITTED_AT)).doesNotThrowAnyException();
    }

    @Test
    void rejectsTomorrow() {
        assertThatThrownBy(() -> rule.check(TODAY.plusDays(1), SUBMITTED_AT))
                .isInstanceOf(InvalidClaimException.class)
                .hasMessage("The date of loss can't be in the future")
                .extracting("field").isEqualTo("loss.date");
    }

    @Test
    void accepts365DaysAgo() {
        assertThatCode(() -> rule.check(TODAY.minusDays(365), SUBMITTED_AT)).doesNotThrowAnyException();
    }

    @Test
    void rejects366DaysAgo() {
        assertThatThrownBy(() -> rule.check(TODAY.minusDays(366), SUBMITTED_AT))
                .isInstanceOf(InvalidClaimException.class)
                .hasMessage("The date of loss can't be more than 365 days ago")
                .extracting("field").isEqualTo("loss.date");
    }

    @Test
    void evaluatesTodayInWinnipegNotUtc() {
        // 9 pm on 17 Oct in Winnipeg is already 18 Oct in UTC
        Instant eveningInWinnipeg = Instant.parse("2026-10-18T02:00:00Z");

        assertThatCode(() -> rule.check(TODAY, eveningInWinnipeg)).doesNotThrowAnyException();
        assertThatThrownBy(() -> rule.check(TODAY.plusDays(1), eveningInWinnipeg))
                .isInstanceOf(InvalidClaimException.class);
    }

    @Test
    void maximumAgeIsConfigurable() {
        LossDateRule thirtyDays = new LossDateRule(WINNIPEG, 30);

        assertThatCode(() -> thirtyDays.check(TODAY.minusDays(30), SUBMITTED_AT)).doesNotThrowAnyException();
        assertThatThrownBy(() -> thirtyDays.check(TODAY.minusDays(31), SUBMITTED_AT))
                .hasMessage("The date of loss can't be more than 30 days ago");
    }
}
