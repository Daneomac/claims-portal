package com.claimsportal.notification.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * The RabbitMQ objects this service owns, and how its listener handles failures (PLAN
 * section 5, ADR 0004).
 *
 * <p>The consumer declares its own queue and binds it to the producer's exchange, so adding
 * a consumer never needs a change in claims-api. Spring's {@code RabbitAdmin} declares all of
 * these when the first connection opens. Declaring something that already exists with the
 * same settings is a no-op; with different settings the broker refuses it.
 *
 * <p>Retry settings (attempts, back-off) are Spring Boot properties under
 * {@code spring.rabbitmq.listener.simple.retry.*} in application.yml. Boot 4 builds the retry
 * interceptor on Spring Framework's core retry support ({@code org.springframework.core.retry});
 * Spring Retry is no longer used.
 */
@Configuration(proxyBeanMethods = false)
class MessagingConfiguration {

    static final String CLAIMS_EVENTS_EXCHANGE = "claims.events";
    static final String CLAIM_SUBMITTED_ROUTING_KEY = "claim.submitted";
    static final String CLAIM_SUBMITTED_QUEUE = "notification.claim-submitted";
    static final String DEAD_LETTER_EXCHANGE = "notification.dlx";
    static final String DEAD_LETTER_QUEUE = "notification.claim-submitted.dlq";

    /**
     * Failures that retrying can't fix, so the message goes straight to the dead-letter queue.
     * The retry policy also finds these when they're the cause of another exception (the
     * container wraps whatever the listener throws in a {@code ListenerExecutionFailedException}).
     */
    static final List<Class<? extends Throwable>> NON_RETRYABLE_EXCEPTIONS = List.of(
            // includes UnprocessableEventException: unsupported version, invalid payload
            AmqpRejectAndDontRequeueException.class,
            // the body isn't JSON, or doesn't fit ClaimSubmittedEvent
            org.springframework.amqp.support.converter.MessageConversionException.class,
            org.springframework.messaging.converter.MessageConversionException.class);

    /**
     * The producer's exchange, declared here too so this service can start before claims-api
     * has ever run. Same settings as claims-api's declaration (durable topic, no arguments).
     */
    @Bean
    TopicExchange claimsEventsExchange() {
        return ExchangeBuilder.topicExchange(CLAIMS_EVENTS_EXCHANGE).durable(true).build();
    }

    /**
     * Durable, so queued messages survive a broker restart. A rejected message is moved to
     * {@code notification.dlx}; the explicit dead-letter routing key means the DLQ binding
     * doesn't depend on the producer's routing keys.
     */
    @Bean
    Queue claimSubmittedQueue() {
        return QueueBuilder.durable(CLAIM_SUBMITTED_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_QUEUE)
                .build();
    }

    @Bean
    Binding claimSubmittedBinding(Queue claimSubmittedQueue, TopicExchange claimsEventsExchange) {
        return BindingBuilder.bind(claimSubmittedQueue).to(claimsEventsExchange).with(CLAIM_SUBMITTED_ROUTING_KEY);
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(DEAD_LETTER_EXCHANGE).durable(true).build();
    }

    /** Where failed messages wait for someone to look at them and replay them (ADR 0004). */
    @Bean
    Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(DEAD_LETTER_QUEUE);
    }

    /**
     * Reads JSON bodies into the listener's parameter type ({@code ClaimSubmittedEvent}), using
     * Spring Boot's {@code JsonMapper}. Boot applies this to the listener container factory.
     */
    @Bean
    MessageConverter jsonMessageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper);
    }

    /** Stops the retry interceptor from retrying failures that can never succeed. */
    @Bean
    RabbitListenerRetrySettingsCustomizer nonRetryableExceptions() {
        return settings -> settings.setExceptionExcludes(NON_RETRYABLE_EXCEPTIONS);
    }

    /** Boot hands this to the retry interceptor, in place of its default recoverer. */
    @Bean
    MessageRecoverer deadLetterRecoverer(MeterRegistry meterRegistry) {
        return new DeadLetterRecoverer(meterRegistry);
    }
}
