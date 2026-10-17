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

The insert is `INSERT … ON CONFLICT (event_id) DO NOTHING`, and its row count is the
duplicate check: 1 means "ours, send it", 0 means "already handled, skip it". It comes
before the send, in the same transaction.

## Alternatives considered
- **Check, then insert (`existsById`, then `save`):** two deliveries handled at the same time
  can both see "not there yet" and both send. The primary key only stops the second one at
  commit, after its email has gone.
- **Insert and catch the duplicate-key exception:** works, but in Postgres a failed statement
  aborts the transaction, and Spring marks it rollback-only, so the skip path would be running
  in a broken transaction. `ON CONFLICT DO NOTHING` reports the same thing without an error.
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
- A concurrent duplicate blocks on the primary key until the first transaction finishes. If
  that one committed, the duplicate skips; if it rolled back because sending failed, the
  duplicate inserts and sends instead. Either way, one email.
- The row lock, and a database connection, are held while the email is sent. SMTP timeouts
  (5 s) bound how long that can take.
- The notification service needs its own small database (`notifications`).
- Production: an email provider with an idempotency key per message would close the
  remaining gap.
