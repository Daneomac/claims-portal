package com.claimsportal.api.infrastructure;

import com.claimsportal.api.domain.LossDateRule;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the plain-Java domain objects and the clock into Spring. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClaimsProperties.class)
class ClaimsConfiguration {

    /** One clock for the whole app, so tests can replace it with a fixed one. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    LossDateRule lossDateRule(ClaimsProperties properties) {
        return new LossDateRule(properties.timeZone(), properties.maxLossAgeDays());
    }
}
