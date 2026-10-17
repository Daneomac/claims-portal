package com.claimsportal.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.listener.ListenerExecutionFailedException;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.boot.retry.RetryPolicySettings;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.mail.MailSendException;

/**
 * Which failures are retried, and what happens when the listener gives up (ADR 0004). The
 * retry policy is built the way Spring Boot builds it: Boot fills a {@link RetryPolicySettings}
 * from the {@code spring.rabbitmq.listener.simple.retry.*} properties, then applies our
 * customizer.
 */
class FailureHandlingTest {

    private final RetryPolicy retryPolicy = retryPolicy();

    @Test
    void retriesAnSmtpFailure() {
        assertThat(retryPolicy.shouldRetry(listenerFailure(new MailSendException("Mail server connection failed"))))
                .isTrue();
    }

    @Test
    void doesNotRetryAnUnprocessableEvent() {
        assertThat(retryPolicy.shouldRetry(listenerFailure(new UnprocessableEventException("Unsupported event"))))
                .isFalse();
    }

    @Test
    void doesNotRetryABodyThatCannotBeConverted() {
        assertThat(retryPolicy.shouldRetry(listenerFailure(new MessageConversionException("Failed to convert"))))
                .isFalse();
    }

    @Test
    void theRecovererCountsTheFailureAndRejectsTheMessageWithoutRequeue() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DeadLetterRecoverer recoverer = new DeadLetterRecoverer(meterRegistry);
        MessageProperties properties = new MessageProperties();
        properties.setMessageId("6f1c2c1e-6a43-4c1e-9a0b-6e7d3f6c2a10");
        Message message = new Message("{}".getBytes(), properties);

        assertThatThrownBy(() -> recoverer.recover(message, new MailSendException("550 <jane.doe@example.com>")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessage("Event 6f1c2c1e-6a43-4c1e-9a0b-6e7d3f6c2a10 was dead-lettered")
                .hasNoCause(); // so the container's warning can't print the SMTP error's text
        assertThat(meterRegistry.get("notifications.failed").counter().count()).isEqualTo(1);
    }

    /** The container wraps whatever the listener throws before the retry interceptor sees it. */
    private static ListenerExecutionFailedException listenerFailure(Throwable cause) {
        return new ListenerExecutionFailedException("Listener threw exception", cause);
    }

    private static RetryPolicy retryPolicy() {
        RetryPolicySettings settings = new RetryPolicySettings();
        settings.setMaxRetries(3L);
        new MessagingConfiguration().nonRetryableExceptions().customize(settings);
        return settings.createRetryPolicy();
    }
}
