package com.claimsportal.api.infrastructure;

import com.claimsportal.api.application.ClaimNumberGenerator;
import com.claimsportal.api.domain.ClaimNumber;
import java.time.Clock;
import java.time.Year;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Claim numbers from a Postgres sequence: unique by construction, even with several API
 * instances. The year is the current year in the business time zone.
 */
@Component
class SequenceClaimNumberGenerator implements ClaimNumberGenerator {

    private final JdbcClient jdbcClient;
    private final Clock clock;
    private final ClaimsProperties properties;

    SequenceClaimNumberGenerator(JdbcClient jdbcClient, Clock clock, ClaimsProperties properties) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public ClaimNumber next() {
        long sequence = jdbcClient.sql("SELECT nextval('claim_number_seq')")
                .query(Long.class)
                .single();
        int year = Year.now(clock.withZone(properties.timeZone())).getValue();
        return ClaimNumber.of(year, sequence);
    }
}
