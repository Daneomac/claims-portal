# 0004 — Bounded retries with back-off, then dead-letter

**Status:** Accepted

## Context
Sending email can fail for transient reasons (SMTP server down, timeouts) or permanent ones
(a malformed or unsupported event). Requeueing forever blocks the queue and hides problems;
dropping messages loses customer confirmations.

## Decision
- **Transient failures:** Spring AMQP listener retry, 4 attempts with exponential back-off
  (1 s, 2 s, 4 s, capped at 10 s).
- **When retries are exhausted:** reject without requeue. The queue's dead-letter exchange
  routes the message to `notification.claim-submitted.dlq`.
- **Non-retryable failures** (unparseable JSON, unknown `eventVersion`, failed payload
  validation) go straight to the DLQ without retrying.
- A `notifications.failed` counter is incremented. The DLQ depth is visible in the RabbitMQ
  UI, and messages can be moved back for replay once the cause is fixed.

## Implementation (Spring Boot 4.1 / Spring AMQP 4.1)
- Spring AMQP's retry interceptor now uses Spring Framework 7's core retry support
  (`org.springframework.core.retry.RetryPolicy`), not Spring Retry. Boot still configures it
  through `spring.rabbitmq.listener.simple.retry.*`, but `max-attempts` was replaced by
  **`max-retries`** (retries after the first attempt). Four attempts is `max-retries: 3`,
  with `initial-interval: 1s`, `multiplier: 2` and `max-interval: 10s`.
- Non-retryable failures: a `RabbitListenerRetrySettingsCustomizer` excludes
  `AmqpRejectAndDontRequeueException` (the base of our `UnprocessableEventException`) and
  both `MessageConversionException` types. The policy matches an excluded type anywhere in
  the cause chain. That matters because the container wraps whatever the listener throws.
- Giving up: Boot passes our `MessageRecoverer` bean to the interceptor. Spring's default
  (`RejectAndDontRequeueRecoverer`) logs the whole message, body included, which would put
  the customer's name and email in the logs. Our `DeadLetterRecoverer` counts the failure,
  logs the event ID and exception type only, and throws a cause-less
  `AmqpRejectAndDontRequeueException`.
- The queue sets `x-dead-letter-exchange: notification.dlx` and an explicit
  `x-dead-letter-routing-key: notification.claim-submitted.dlq`, which is also the DLQ's
  binding key, so dead-lettering doesn't depend on the producer's routing keys.
- Replay: in the management UI, use "Get messages" on the DLQ and publish the payload to
  `claims.events` with routing key `claim.submitted` and the same `message_id`. The
  idempotent consumer (ADR 0003) makes an accidental double replay harmless. Moving messages
  in bulk needs the shovel plugin, which isn't enabled.

## Alternatives considered
- **Delayed retry queues** (per-delay queues with a TTL that dead-letter back to the main
  queue): retries don't hold a consumer thread, so they're better at volume. More topology to
  explain; this is the next step if retry volume grows.
- **Requeue on failure (the RabbitMQ default):** a hot loop on a poison message.
- **A retry table in the database:** duplicates what the broker already does.

## Consequences
- Retrying in the listener holds that consumer thread for up to ~7 s. That's acceptable with
  a few concurrent consumers and low volume.
- No message is silently lost: it's either processed or sitting in the DLQ.
- In production the DLQ needs alerting and a runbook for replay.
