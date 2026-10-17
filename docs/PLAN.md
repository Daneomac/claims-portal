# Claims Portal — Architecture & Build Plan

Working plan for the Red River Mutual Software Developer III challenge. Key decisions are
recorded as ADRs in [`docs/adr/`](adr/). This document becomes the basis for the README's
architecture section once the build is done.

## 1. Goal and scope

A small, polished vertical slice: a customer submits a home insurance claim in a web form,
the API validates and stores it with a unique claim number, a `ClaimSubmitted` event is
published to RabbitMQ, and a **separate** notification service consumes the event and emails
the customer their claim number.

**Guiding principle:** every piece should be something I can explain and defend in the review.
Prefer a few well-chosen patterns with tests over breadth. Items marked *(stretch)* are only
done if the core is finished and polished.

**Out of scope:** authentication/customer accounts, policy look-up against a real policy
system, file uploads, claim status tracking, admin UI.

## 2. Technology choices

| Concern | Choice | Version (Oct 2026) |
|---|---|---|
| Language / runtime | Java (LTS) | 25 |
| Backend framework | Spring Boot (Spring Framework 7, Jackson 3), Maven wrapper | 4.1.1 |
| Frontend | React + TypeScript + Vite | React 19.3, Vite 8.3 |
| Form handling | react-hook-form + zod | 7.90 / 4.6 |
| Database | PostgreSQL + Flyway migrations | 17 |
| Message broker | RabbitMQ (with management plugin) | 4.x |
| Email | Mailpit (SMTP capture + web inbox + REST API) | latest pinned tag |
| Web server | nginx serving the built SPA and reverse-proxying `/api` | stable-alpine |
| Containers | Docker Compose | v5 |
| Backend tests | JUnit 5, Mockito, AssertJ, Testcontainers, ArchUnit | — |
| Frontend tests | Vitest, React Testing Library, MSW | Vitest 5 |
| End-to-end tests | Playwright against the Compose stack | 1.64 |
| CI | GitHub Actions + Dependabot | — |

Java 25 and Spring Boot 4.1 are both current, supported releases; Spring Initializr offers this
combination by default.

## 3. Architecture

```
 Browser
   │  http://localhost:3000
   ▼
 ┌─────────────────────────┐   /api/*    ┌──────────────────────────┐     ┌──────────────┐
 │ claims-web (nginx)      │ ──────────▶ │ claims-api  :8080         │────▶│ PostgreSQL   │
 │ static React SPA        │ ◀────────── │ validate → number → save  │     │ db: claims   │
 └─────────────────────────┘ 201 + claim │ claim + outbox row (1 tx) │     │ db: notific. │
                              number     │ OutboxRelay → publish     │     └──────────────┘
                                         └────────────┬─────────────┘            ▲
                                                      │ ClaimSubmitted v1         │
                                                      ▼ (persistent, confirmed)   │
                              ┌────────────────────────────────────────┐         │
                              │ RabbitMQ  exchange: claims.events      │         │
                              │  └▶ queue: notification.claim-submitted│         │
                              │      └ dead-letter ▶ ...-submitted.dlq │         │
                              └───────────────────┬────────────────────┘         │
                                                  ▼                               │
                              ┌────────────────────────────────────────┐         │
                              │ notification-service  :8081 (actuator) │─────────┘
                              │ consume → dedupe → render → send       │ processed_event
                              └───────────────────┬────────────────────┘
                                                  │ SMTP
                                                  ▼
                                       ┌─────────────────────┐
                                       │ Mailpit  :8025 (UI) │
                                       └─────────────────────┘
```

### Key decisions (see ADRs)

| # | Decision | Why it matters |
|---|---|---|
| [0001](adr/0001-rabbitmq-message-broker.md) | RabbitMQ as the broker | Simple, built-in dead-lettering, great Spring support |
| [0002](adr/0002-transactional-outbox.md) | Transactional outbox for publishing | No "saved but never published" dual-write bug |
| [0003](adr/0003-at-least-once-idempotent-consumer.md) | At-least-once delivery + idempotent consumer | Duplicates are expected; the consumer makes them harmless |
| [0004](adr/0004-retry-and-dead-letter.md) | Bounded retries with back-off, then DLQ | Transient failures recover; poison messages don't block or vanish |
| [0005](adr/0005-event-contract-per-service.md) | Event contract defined per service, no shared library | Services build, version and deploy independently |
| [0006](adr/0006-same-origin-reverse-proxy.md) | SPA served by nginx, `/api` proxied (same origin) | No CORS, one public entry point, security headers in one place |

### Repository layout

```
claims-portal/
├── claims-api/              Spring Boot — REST, validation, persistence, outbox, publishing
├── notification-service/    Spring Boot — event consumer, idempotency, email rendering/sending
├── claims-web/              React + TS — claim form, confirmation; Dockerfile + nginx.conf
├── e2e/                     Playwright tests against the running Compose stack
├── infra/postgres/          init script: one database + one user per service
├── docs/                    PLAN.md, adr/, events.md, architecture diagram
├── .github/                 workflows/ci.yml, dependabot.yml
├── docker-compose.yml
├── .env.example             local-only values; copied to .env (git-ignored)
├── .gitattributes           LF for shell scripts / mvnw
└── README.md
```

Java base packages: `com.claimsportal.api` and `com.claimsportal.notification`.

### Ports (local)

| Component | Port | Notes |
|---|---|---|
| claims-web | 3000 | The customer-facing entry point |
| claims-api | 8080 | Also exposed directly for curl/HTTP-file demos |
| notification-service | 8081 | Actuator only (health, metrics) |
| PostgreSQL | 5432 | |
| RabbitMQ | 5672 / 15672 | AMQP / management UI |
| Mailpit | 1025 / 8025 | SMTP / inbox UI + API |

## 4. claims-api

### Layers (package-by-layer, enforced by an ArchUnit test)

| Package | Contents | May depend on |
|---|---|---|
| `web` | `ClaimController`, request/response records, `ApiExceptionHandler` | `application`, `domain` (value types and `InvalidClaimException`) |
| `application` | `ClaimSubmissionService`, `SubmitClaimCommand`, ports: `ClaimNumberGenerator`, `ClaimRepository`, `EventOutbox` | `domain` |
| `domain` | `Claim`, `ClaimNumber`, `Loss`, `LossType`, `Province`, `Address`, `Policyholder`, `LossDateRule`, `ClaimSubmitted` event | nothing (plain Java) |
| `infrastructure` | JPA entities and repositories, `SequenceClaimNumberGenerator`, `JpaEventOutbox`, `OutboxRelay`, `RabbitEventPublisher`, RabbitMQ config | `application`, `domain` |

The use case in one transaction:

```java
@Transactional
public SubmittedClaim submit(SubmitClaimCommand command) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Claim claim = Claim.submit(claimNumberGenerator.next(), command.policyNumber(), command.policyholder(),
            command.loss(), command.propertyAddress(), now, lossDateRule);   // domain rules live here
    claims.save(claim);
    outbox.append(ClaimSubmitted.from(claim, now));   // same transaction as the claim
    return SubmittedClaim.from(claim);
}
```

The domain never reads a clock: the service passes in the submission instant, and
`LossDateRule` works out "today" from it in `America/Winnipeg`.

### REST API

`POST /api/v1/claims` — `Content-Type: application/json`

```json
{
  "policyholder": { "firstName": "Jane", "lastName": "Doe",
                    "email": "jane.doe@example.com", "phone": "204-555-0142" },
  "policyNumber": "RRM-12345678",
  "loss": { "date": "2026-10-12", "type": "WATER",
            "description": "Burst pipe in the basement flooded the finished rec room.",
            "estimatedAmount": 8500.00 },
  "propertyAddress": { "street": "123 Main St", "unit": null, "city": "Winnipeg",
                       "province": "MB", "postalCode": "R3C 1A1" },
  "declarationAccepted": true
}
```

| Outcome | Response |
|---|---|
| Valid | `201 Created` → `{ "claimNumber": "HC-2026-000123", "status": "RECEIVED", "submittedAt": "..." }` |
| Invalid fields | `400` RFC 9457 `ProblemDetail` with `errors: [{ "field": "loss.date", "message": "..." }]` |
| Malformed JSON / wrong type | `400` `ProblemDetail`, no internals leaked |
| Unexpected error | `500` `ProblemDetail` with a generic message and the trace ID |

No `GET /claims/{number}`: without authentication it would expose customer PII to anyone who
guesses a claim number. That's why there's no `Location` header either.

### Validation rules

Server-side validation (Jakarta Bean Validation + domain checks) is authoritative. The zod
schema in the web app mirrors it for UX only.

| Field | Rule |
|---|---|
| First / last name | required, trimmed, 1–100 chars, letters (any language), spaces, `'` `-` `.` |
| Email | required, valid address, ≤ 254 chars, stored lower-cased |
| Phone | optional, North American format |
| Policy number | required, `RRM-` + 8 digits, normalized to upper case |
| Loss date | required, not in the future, not more than 365 days ago (configurable); "today" is evaluated in `America/Winnipeg` using an injected `Clock` |
| Loss type | required: `FIRE`, `WATER`, `WIND_HAIL`, `THEFT`, `VANDALISM`, `OTHER` |
| Description | required, 20–2000 chars |
| Estimated amount | optional, > 0, ≤ 10,000,000, max 2 decimals |
| Street / unit / city | required ≤ 200 / optional ≤ 20 / required ≤ 100 |
| Province | required, one of the 13 Canadian province/territory codes |
| Postal code | required, Canadian format (`A1A 1A1`), normalized |
| Declaration | must be `true` ("the information provided is accurate") |

### Claim number

`HC-{year}-{6+ digits}` from a Postgres sequence, e.g. `HC-2026-000123`. It's unique by
construction, short enough to read over the phone, and hidden behind `ClaimNumberGenerator`.
It's sequential and therefore guessable, which is acceptable because nothing uses it as a
credential (see the missing GET endpoint above). The year comes from the injected `Clock`. The
sequence doesn't reset each year; a yearly reset is a possible production change.

### Persistence (Flyway `V1__init.sql`)

- `claim` — UUID PK, `claim_number` (unique), policyholder, loss and address columns,
  `status`, `submitted_at` (`timestamptz`, UTC).
- `claim_number_seq` — sequence.
- `outbox_event` — `id` (UUID = event ID), `aggregate_id`, `event_type`, `event_version`,
  `payload` (`jsonb`), `trace_parent`, `created_at`, `published_at` (null until sent),
  `attempts`, `last_error`.
  Partial index on `created_at WHERE published_at IS NULL`.

### Outbox relay

- `@Scheduled` every 500 ms: `SELECT … WHERE published_at IS NULL ORDER BY created_at LIMIT 50
  FOR UPDATE SKIP LOCKED` so several API instances can run it without double-publishing a row
  at the same time.
- Publish each row as a persistent message with **publisher confirms** and mandatory
  returns. The row is marked `published_at` only after the broker acks.
- On failure: increment `attempts`, record `last_error` (no PII), and leave the row for the
  next pass. A gauge reports the unpublished count so a stalled relay is visible.
- Result: at-least-once publishing. A crash between the ack and the update republishes once,
  which the consumer handles (ADR 0003).
- Production notes: purge published rows on a schedule; CDC (e.g. Debezium) instead of
  polling at higher volume.

## 5. Event contract — `ClaimSubmitted` v1

AMQP message properties: `messageId` = `eventId`, `type` = `ClaimSubmitted`,
`contentType` = `application/json`, `deliveryMode` = persistent; header `eventVersion: 1`;
`traceparent` is added automatically by Micrometer tracing.

```json
{
  "eventId": "6f1c2c1e-6a43-4c1e-9a0b-6e7d3f6c2a10",
  "eventType": "ClaimSubmitted",
  "eventVersion": 1,
  "occurredAt": "2026-10-16T18:04:11.532Z",
  "data": {
    "claimNumber": "HC-2026-000123",
    "policyNumber": "RRM-12345678",
    "policyholder": { "firstName": "Jane", "lastName": "Doe", "email": "jane.doe@example.com" },
    "lossType": "WATER",
    "lossDate": "2026-10-12",
    "submittedAt": "2026-10-16T18:04:11.501Z"
  }
}
```

The event carries the minimum the notification needs, and deliberately not the damage
description, phone number or address. The alternative, a "thin" event plus a call back to
the API for details, would couple the services at runtime and need an endpoint that exposes
PII. Compatibility rule: additive changes keep v1; breaking changes publish v2 alongside it.

**RabbitMQ topology:**

| Object | Type | Declared by |
|---|---|---|
| `claims.events` | durable topic exchange | claims-api (the producer owns its exchange) |
| `notification.claim-submitted` | durable queue, binding key `claim.submitted`, DLX = `notification.dlx` | notification-service (the consumer owns its queue) |
| `notification.dlx` → `notification.claim-submitted.dlq` | direct exchange + durable queue | notification-service |

## 6. notification-service

### Layers

| Package | Contents |
|---|---|
| `messaging` | `ClaimSubmittedListener` (`@RabbitListener`), event records, topology config |
| `application` | `ClaimConfirmationService`, port `EmailSender`, `ConfirmationEmail` model |
| `email` | `SmtpEmailSender` (`JavaMailSender`), `ConfirmationEmailRenderer` (Thymeleaf HTML + plain text) |
| `persistence` | `ProcessedEventRepository` (`processed_event`: `event_id` PK, `claim_number`, `processed_at`) |

### Processing a message

```
begin tx
  insert processed_event(event_id)      ── duplicate key? → already handled: ack and skip
  render email (HTML + text)            ── first name escaped by Thymeleaf
  send via SMTP                         ── throws? → tx rolls back → retry (ADR 0004)
commit → ack
```

- A concurrent duplicate blocks on the primary key until the first transaction commits, then
  sees the conflict and skips.
- Remaining gap: if the process dies after the SMTP send but before the commit, the
  redelivered message sends a second email. Email can't take part in a DB transaction, so this
  is the accepted at-least-once trade-off (documented in ADR 0003).

**Email content:** subject "We've received your claim HC-2026-000123". The body has a greeting
by first name, the claim number (prominent), loss type and date, the policy number masked
(`RRM-****5678`), and next steps (an adjuster will contact you within 2 business days). The
damage description isn't included. The sender address is configurable and defaults to
`claims-noreply@example.com`.

### Retries and dead-lettering (ADR 0004)

Spring AMQP listener retry: 4 attempts with exponential back-off (1 s → 2 s → 4 s, capped at
10 s), then reject without requeue, which sends the message to the DLQ. Unrecoverable errors
(unparseable JSON, unknown `eventVersion`, invalid payload) go **straight** to the DLQ. Prefetch
is 10. Ops can inspect and replay the DLQ from the RabbitMQ management UI.

*(Verify in milestone 4: Boot 4 / Spring AMQP 4 retry configuration, now that Spring Retry has
moved to Spring Framework's core retry support.)*

The notification service has its own `notifications` database, so services don't share
tables.

## 7. claims-web

- One page, no router: **form → confirmation panel**. On success the form is replaced with
  "Claim received". It shows the claim number and "We've sent a confirmation to
  jane.doe@example.com — please check your email (and spam folder)", with a "Submit another
  claim" button.
- **Form UX:** grouped `fieldset`s (About you · Your policy · What happened · Property
  address), native date input with `max` = today, province select, character counter on the
  description, submit button disabled while a request is in flight (prevents double
  submissions).
- **Errors:** inline field errors on blur and on submit; an error summary at the top that takes
  focus on a failed submit; server-side `400` field errors mapped back onto their inputs;
  network/5xx errors shown as a retryable banner that keeps the entered data.
- **Accessibility:** a `<label>` for every input, `aria-invalid` + `aria-describedby` for
  errors, the error summary and confirmation announced via focus/live region, visible focus
  styles, keyboard-only flow, and colour contrast meeting WCAG AA.
- **Styling:** plain CSS modules, no UI library (small and easy to explain).
- **API client:** a small typed `submitClaim()` wrapper around `fetch`, using relative `/api`
  URLs. Vite's dev-server proxy and nginx in Compose both forward to claims-api.

## 8. Security and customer data

| Area | Measure |
|---|---|
| Secrets | None in the repo. Compose reads `${VAR}` from `.env` (git-ignored); `.env.example` has obviously local-only values. Each service gets its own DB user with access only to its own database |
| PII in logs | Log claim number, event ID and trace ID only. Request/event records override `toString()` so names and emails can't leak through an accidental `log.info("{}", request)`. A test asserts this |
| PII in events/email | Only the fields needed (section 5). Policy number masked in the email |
| Input | Validation + length limits on every field; JPA parameter binding; nginx `client_max_body_size 16k` |
| Output | React and Thymeleaf escape by default. `ProblemDetail` responses never include stack traces |
| Abuse | nginx `limit_req` on `/api` (per-IP rate limit) |
| Headers | nginx sets CSP, `X-Content-Type-Options`, `Referrer-Policy`, `frame-ancestors 'none'` |
| Containers | JRE-only / alpine images, non-root user, actuator not routed through nginx |
| Supply chain | Dependabot for Maven, npm, Docker images and Actions |
| Production notes | Customer authentication, TLS everywhere, secrets manager, encryption at rest, data retention policy, CAPTCHA, policy verification |

## 9. Observability

- **Health:** Actuator liveness/readiness, including DB and RabbitMQ indicators. Compose
  `healthcheck`s plus `depends_on: condition: service_healthy`, so services start in order.
- **Structured logs:** Spring Boot's built-in structured logging
  (`logging.structured.format.console=ecs`), giving JSON lines with trace/span IDs.
- **Tracing:** Micrometer Tracing (OpenTelemetry bridge) with RabbitMQ template/listener
  observations enabled, so **one trace ID follows a claim from the HTTP request, through the
  outbox and the broker, into the notification logs**. No collector is needed to show this in
  logs. *(stretch: OTLP → Jaeger container for a visual trace)*
- **Metrics:** `/actuator/prometheus` with counters `claims.submitted`,
  `notifications.sent`, `notifications.failed`, `notifications.duplicates_skipped` and a gauge
  `outbox.pending`. *(stretch: Prometheus + Grafana in Compose)*

Note: the outbox relay runs on a scheduler, so the trace context is saved in the outbox row
(`trace_parent` column) and restored when publishing, to keep the trace continuous.

## 10. Testing strategy

TDD for domain rules and the two use-case services; test-after for framework wiring.

| Level | Where | Most important cases |
|---|---|---|
| Unit (domain) | claims-api | each validation rule's boundaries (loss date today / tomorrow / 365 / 366 days ago); claim number format; postal code / policy normalization |
| Unit (application) | claims-api | `submit` saves the claim **and** appends a matching outbox event; nothing is appended if saving fails |
| Web slice | claims-api `@WebMvcTest` | 201 + body on valid input; 400 `ProblemDetail` with field paths; malformed JSON → 400; no PII in logs |
| Integration | claims-api + Testcontainers (Postgres, RabbitMQ) | submit → row in `claim` + `outbox_event` → relay publishes → message on a test queue with the right properties; broker down → row stays pending, then publishes after recovery |
| Unit | notification-service | email contains the claim number and first name; masked policy; duplicate event skipped; SMTP failure propagates (for retry); unknown version → non-retryable |
| Integration | notification-service + Testcontainers (Postgres, RabbitMQ, Mailpit) | publish event → email in Mailpit containing the claim number; same event twice → one email; SMTP down → retries → DLQ |
| Architecture | both, ArchUnit | layer dependency rules; `domain` has no Spring/JPA imports |
| Component | claims-web, Vitest + RTL + MSW | required-field errors; date-in-future error; successful submit shows the claim number and focuses the confirmation; server 400 maps to fields; 500 shows the banner and keeps the data |
| End-to-end | e2e/, Playwright vs Compose | fill the form in a real browser → confirmation shows a claim number → Mailpit API has an email to that address containing **the same** claim number |

The end-to-end test is the automated version of the interview demo.

## 11. CI/CD (GitHub Actions)

- `claims-api` and `notification-service` jobs run `./mvnw verify` (unit + Testcontainers tests;
  Docker is available on `ubuntu-latest`).
- `claims-web` job: `npm ci`, lint, type-check, test, build.
- `e2e` job (after the above): `docker compose up --build --wait`, Playwright, upload the HTML
  report and Compose logs as artifacts if it fails.
- Maven/npm caching; runs on pushes and PRs.
- *(stretch)* build and push images to GHCR on the default branch.

## 12. Milestones

Each one ends green and committed; I review each commit before the next one starts.

| # | Milestone | Done when |
|---|---|---|
| 1 | **Scaffold** — `.gitattributes`, `.editorconfig`, `.env.example`, Compose with Postgres (2 DBs), RabbitMQ, Mailpit; generate both Spring Boot projects (Initializr, Boot 4.1.1, Java 25) and the Vite app | `docker compose up` shows healthy infra; both services boot and `./mvnw test` passes; `npm test` passes |
| 2 | **claims-api core** — domain, validation, claim number, Flyway, REST endpoint, error handling | Unit + web slice + DB integration tests pass; curl demo returns a claim number |
| 3 | **Outbox + publishing** — outbox table, relay, confirms, topology, metrics | Integration test sees the message on a queue; broker-down test passes |
| 4 | **notification-service** — listener, idempotency, templates, SMTP, retry + DLQ | Email visible in Mailpit; duplicate and DLQ tests pass |
| 5 | **claims-web** — form, validation, confirmation, error states, accessibility | Component tests pass; manual keyboard/screen-reader pass |
| 6 | **Containerize** — Dockerfiles (multi-stage, non-root), nginx config, full Compose | `docker compose up --build` → full workflow works in the browser |
| 7 | **Observability** — structured logs, tracing across the broker, health checks | One trace ID visible in both services' logs for a submission |
| 8 | **E2E + CI** — Playwright suite, GitHub Actions, Dependabot | Pipeline green on GitHub |
| 9 | **Docs** — README (prerequisites, run, test, demo script, architecture, trade-offs, production improvements, AI usage statement), finished ADRs, `docs/events.md` | A fresh clone works by following the README alone |
| 10 | *(stretch)* Jaeger/Grafana, API `Idempotency-Key`, GHCR images, free-tier cloud deployment | — |

## 13. Interview prep (to fill in as we go)

- Demo script: form → validation errors → submit → confirmation → Mailpit email → RabbitMQ UI
  → logs with one trace ID → stop Mailpit, submit, show retries/DLQ → restart and replay.
- Likely questions: why the outbox; what happens if X is down (DB, broker, SMTP, consumer);
  how this scales (more consumers on the queue, relay with SKIP LOCKED, partitioning); what's
  missing for production (section 8 notes, auth, monitoring/alerting, DLQ runbook).

## 14. Schedule and decisions log

- **Deadline: Sunday 25 Oct 2026.** Target: milestones 1–9 done by Thu 22 Oct, leaving
  Fri–Sat for stretch items, a dry-run of the demo and interview prep.
- **Branching:** work directly on `Dev` (the default branch), one commit per milestone or
  smaller.
- **Plan accepted** as written on 17 Oct 2026.
- Open: cloud deployment — decide after milestone 9 based on remaining time.
