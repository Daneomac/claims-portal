# 0001 — RabbitMQ as the message broker

**Status:** Accepted

## Context
The challenge requires a Claim Submitted event to be published to a message queue and
handled by a separate subscriber service. It must run locally at no cost. There's one event
type and one consumer today; more consumers (e.g. adjuster assignment, fraud screening) are
plausible later.

## Decision
Use RabbitMQ in a container, with a durable topic exchange `claims.events` owned by the
producer, and one durable queue per consuming service bound to it.

## Alternatives considered
- **Apache Kafka:** strong for high-throughput streams and replay, but heavier to run and
  operate, and retries/dead-lettering must be built by hand. More than one event type needs.
- **Cloud queues (SQS, Azure Service Bus):** realistic in production, but need accounts and
  credentials and don't run fully offline. LocalStack is an option but adds friction.
- **In-process events (Spring application events / Modulith):** don't meet the
  "separate subscriber service" requirement.

## Consequences
- First-class Spring AMQP support: listener containers, publisher confirms, retry.
- Native dead-letter exchanges make failure handling (ADR 0004) configuration, not code.
- The management UI (`:15672`) makes the async flow visible during the demo.
- A topic exchange lets new consumers subscribe without any change to the producer.
- No long-term event replay like Kafka offers; the database is the system of record.
