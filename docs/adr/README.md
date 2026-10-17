# Architecture Decision Records

Short records of the significant decisions in this project: the context, what was decided,
the alternatives, and the consequences. Format: [Michael Nygard's ADR template](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions).

| ADR | Title | Status |
|---|---|---|
| [0001](0001-rabbitmq-message-broker.md) | RabbitMQ as the message broker | Accepted |
| [0002](0002-transactional-outbox.md) | Transactional outbox for event publishing | Accepted |
| [0003](0003-at-least-once-idempotent-consumer.md) | At-least-once delivery with an idempotent consumer | Accepted |
| [0004](0004-retry-and-dead-letter.md) | Bounded retries with back-off, then dead-letter | Accepted |
| [0005](0005-event-contract-per-service.md) | Event contract defined per service | Accepted |
| [0006](0006-same-origin-reverse-proxy.md) | Serve the SPA and proxy the API from one origin | Accepted |
