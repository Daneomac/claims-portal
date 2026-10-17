# 0005 — Event contract defined per service

**Status:** Accepted

## Context
Both services need a Java type for `ClaimSubmitted`. One option is a shared library; the
other is for each service to define its own type against a documented contract.

## Decision
Each service defines its own record for the event, and the contract is documented in
`docs/events.md` (JSON shape, AMQP properties, versioning rules). Changes are made
compatibly: additive fields stay within v1 and consumers ignore unknown fields; a breaking
change publishes v2 alongside v1 until all consumers have moved.

## Alternatives considered
- **Shared `claims-events` jar:** one definition and compile-time safety, but every change
  requires coordinated releases, and services built in other languages can't use it.
- **Schema registry (JSON Schema / Avro):** the right tool once there are many producers and
  consumers. It's extra infrastructure for one event.

## Consequences
- The services build, version and deploy independently (separate Maven projects, no shared
  code).
- A small amount of duplication (one record per side).
- Contract drift is caught by tests: the producer test asserts the JSON it publishes, and the
  consumer test parses a fixture of the same JSON. *(Possible upgrade: consumer-driven contract
  tests with Pact.)*
