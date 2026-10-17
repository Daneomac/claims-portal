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
