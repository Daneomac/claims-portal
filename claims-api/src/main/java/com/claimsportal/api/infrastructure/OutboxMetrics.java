package com.claimsportal.api.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * The {@code outbox.pending} gauge: events saved but not yet confirmed by the broker. A number
 * that keeps growing means the relay is stuck, e.g. because the broker is down (ADR 0002).
 * Spring Boot registers every {@link MeterBinder} bean with the meter registry.
 */
@Component
class OutboxMetrics implements MeterBinder {

    private final OutboxEventRepository outbox;

    OutboxMetrics(OutboxEventRepository outbox) {
        this.outbox = outbox;
    }

    /** The count is queried each time the gauge is read, e.g. on a Prometheus scrape. */
    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("outbox.pending", outbox, OutboxEventRepository::countByPublishedAtIsNull)
                .description("Outbox events not yet published to the broker")
                .register(registry);
    }
}
