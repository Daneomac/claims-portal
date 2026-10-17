package com.claimsportal.api.infrastructure;

import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The RabbitMQ objects this service owns (PLAN section 5). The producer declares only its
 * exchange; each consumer declares and binds its own queue, so adding a consumer never needs a
 * change here.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxProperties.class)
class MessagingConfiguration {

    static final String CLAIMS_EVENTS_EXCHANGE = "claims.events";

    /**
     * Durable, so it survives a broker restart. Spring's {@code RabbitAdmin} declares it when
     * the first connection opens; declaring an exchange that already exists is a no-op.
     */
    @Bean
    TopicExchange claimsEventsExchange() {
        return ExchangeBuilder.topicExchange(CLAIMS_EVENTS_EXCHANGE).durable(true).build();
    }
}
