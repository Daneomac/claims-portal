package com.claimsportal.notification;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/** Tracing and metrics settings that need code rather than a property (PLAN section 9). */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfiguration {

    /**
     * Doesn't observe requests to {@code /actuator}: the Compose health checks call it every
     * 10 seconds, and a trace for each would bury the interesting ones in Jaeger. This is the
     * approach the Spring Boot reference documentation shows for the same problem.
     */
    @Bean
    ObservationPredicate noActuatorRequestObservations() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext request
                && request.getCarrier().getRequestURI().startsWith("/actuator"));
    }
}
