# 0003 — At-least-once delivery with an idempotent consumer

**Status:** Accepted

## Context
The outbox (ADR 0002) and broker redelivery (after a consumer crash or a missed ack) both
mean the same `ClaimSubmitted` event can arrive more than once. "Exactly once" isn't
achievable end to end when the side effect is an email sent over SMTP.

## Decision
Accept at-least-once delivery and make the notification service idempotent. Each event has
a unique `eventId`, also set as the AMQP `messageId`. In one database transaction, the
consumer inserts `eventId` into `processed_event` (primary key), sends the email, and
commits. A duplicate key means the event was already handled, so the consumer acks and
skips it.

## Alternatives considered
- **Record after sending, in a separate transaction:** a crash between the two still causes
  a duplicate, and concurrent duplicates could both send.
- **Dedupe in memory or with a Redis TTL key:** lost on restart / another component to run.
- **Broker-side deduplication plugin:** not part of core RabbitMQ, and it only covers
  publish-side duplicates.

## Consequences
- Duplicates are harmless in every case except one: the process dies after SMTP accepted the
  message but before the commit. Then one duplicate email is possible. This is the documented,
  accepted trade-off, and much better than a missing confirmation.
- If sending fails, the transaction rolls back, so the event isn't marked processed and the
  retry (ADR 0004) can succeed later.
- A concurrent duplicate blocks on the primary key until the first transaction finishes, then
  skips.
- The notification service needs its own small database (`notifications`).
- Production: an email provider with an idempotency key per message would close the
  remaining gap.
