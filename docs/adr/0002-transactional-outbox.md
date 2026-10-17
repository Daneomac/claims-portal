# 0002 — Transactional outbox for event publishing

**Status:** Accepted

## Context
After saving a claim, the API must publish `ClaimSubmitted`. Writing to the database and
publishing to the broker are two separate systems, so there is no shared transaction:
- publish inside the DB transaction, then the commit fails → an event for a claim that
  doesn't exist;
- commit, then the publish fails or the process crashes → a saved claim the customer is
  never emailed about.

## Decision
Write the event to an `outbox_event` table **in the same transaction** as the claim. A
scheduled `OutboxRelay` reads unpublished rows (`FOR UPDATE SKIP LOCKED`), publishes them with
publisher confirms, and marks each row published only after the broker acknowledges it.

## Alternatives considered
- **Publish after commit** (`@TransactionalEventListener(AFTER_COMMIT)`): simple, but the event
  is lost if the broker is down or the app crashes right after the commit.
- **Change data capture (Debezium):** reads the outbox from the database's write-ahead log,
  with lower latency and no polling. It's the right step at scale, but adds Kafka Connect
  infrastructure that's out of proportion here.
- **Spring Modulith event publication registry:** a similar guarantee with less code, but it
  hides the mechanism I want to be able to show and explain.

## Consequences
- A claim is never saved without an event eventually being published. The API keeps
  accepting claims while the broker is down; they publish once it's back.
- Delivery becomes **at-least-once** (a crash between the ack and the update republishes),
  which ADR 0003 handles.
- Up to ~500 ms of added latency from polling. That's fine for an email confirmation.
- The `outbox.pending` gauge exposes a stalled relay; published rows need periodic cleanup
  in production.
- The trace context is stored on the row so a trace continues across the asynchronous gap.
