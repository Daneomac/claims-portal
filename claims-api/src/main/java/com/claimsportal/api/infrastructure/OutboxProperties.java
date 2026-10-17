package com.claimsportal.api.infrastructure;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Outbox relay settings under {@code claims.outbox.*} in application.yml.
 *
 * @param relayEnabled   whether the relay runs on a schedule; tests can switch it off
 * @param pollDelay      the pause between the end of one relay run and the start of the next
 * @param batchSize      the most events one run publishes, all in one database transaction
 * @param confirmTimeout how long to wait for the broker to confirm a message before giving up
 */
@Validated
@ConfigurationProperties("claims.outbox")
public record OutboxProperties(
        boolean relayEnabled,
        @NotNull Duration pollDelay,
        @Min(1) @Max(1000) int batchSize,
        @NotNull Duration confirmTimeout) {
}
