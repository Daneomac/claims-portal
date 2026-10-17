package com.claimsportal.api.infrastructure;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Business settings under {@code claims.*} in application.yml.
 *
 * @param timeZone       the zone used to decide what "today" is, e.g. America/Winnipeg
 * @param maxLossAgeDays how many days after a loss a claim can still be submitted
 */
@Validated
@ConfigurationProperties("claims")
public record ClaimsProperties(@NotNull ZoneId timeZone, @Min(0) int maxLossAgeDays) {
}
