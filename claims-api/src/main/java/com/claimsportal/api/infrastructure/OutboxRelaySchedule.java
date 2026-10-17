package com.claimsportal.api.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the outbox relay on a timer. Switched off with {@code claims.outbox.relay-enabled=false}
 * for tests that check the outbox rows without a relay racing them.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty(name = "claims.outbox.relay-enabled", matchIfMissing = true)
class OutboxRelaySchedule {

    private final OutboxRelay relay;

    OutboxRelaySchedule(OutboxRelay relay) {
        this.relay = relay;
    }

    /**
     * A fixed <em>delay</em> (not rate) starts the next run only after the previous one has
     * finished, so runs never overlap even when the broker is slow. Reads the same property as
     * {@link OutboxProperties#pollDelay()}, since annotations can only use placeholders. If a run
     * throws (e.g. the database is down), Spring logs it and the next run still happens.
     */
    @Scheduled(fixedDelayString = "${claims.outbox.poll-delay}")
    void publishPendingEvents() {
        relay.publishPendingEvents();
    }
}
