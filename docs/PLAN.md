# Claims Portal — Architecture & Build Plan

Working plan for the Red River Mutual Software Developer III challenge. Key decisions are
recorded as ADRs in [`docs/adr/`](adr/). It was written before the build and updated as each
milestone landed; where it and the code ever disagreed, the code won and this file was fixed.
The [README](../README.md) is the front door; this is the detailed design.

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
| Message broker | RabbitMQ (with management plugin) | 4.3 |
| Email | Mailpit (SMTP capture + web inbox + REST API) | latest pinned tag |
| Web server | nginx (unprivileged image) serving the built SPA and reverse-proxying `/api` | 1.30-alpine |
| Containers | Docker Compose | v5 |
| Tracing | Jaeger v2 (all-in-one, in-memory) with OpenTelemetry/Micrometer in the apps | 2.22 |
| Backend tests | JUnit 5, Mockito, AssertJ, Testcontainers, ArchUnit | — |
| Frontend tests | Vitest, React Testing Library, MSW, oxlint | Vitest 5 |
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

Not drawn: Jaeger (`:16686`). Both services send their spans to it over OTLP/HTTP (section 9).

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
├── docs/                    PLAN.md, adr/ (decision records), events.md (event contract)
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
| Jaeger | 16686 | Trace UI (the apps send spans to 4318 on the Compose network) |

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
- A publish fails on a nack, a return (nothing bound to the exchange) or no confirm within
  `claims.outbox.confirm-timeout` (5 s). On failure: increment `attempts` (failed attempts
  only), record `last_error` (no PII), leave the row for the next pass, and **stop the batch**.
  Failures are almost always broker-wide, so trying the rest would only wait out more
  timeouts while holding the row locks. Rows published before the failure still commit.
- A gauge (`outbox.pending`) reports the unpublished count so a stalled relay is visible.
- Classes (`infrastructure`): `OutboxRelay` (the transactional batch), `OutboxRelaySchedule`
  (`@Scheduled` fixed delay, off with `claims.outbox.relay-enabled=false`),
  `OutboxEventRepository` (native `SKIP LOCKED` query), `EventPublisher` port +
  `RabbitEventPublisher`, `MessagingConfiguration` (declares `claims.events`). Settings live
  in `OutboxProperties` under `claims.outbox.*`.
- Result: at-least-once publishing. A crash between the ack and the update republishes once,
  which the consumer handles (ADR 0003).
- Production notes: purge published rows on a schedule; CDC (e.g. Debezium) instead of
  polling at higher volume.

## 5. Event contract — `ClaimSubmitted` v1

AMQP message properties: `messageId` = `eventId`, `type` = `ClaimSubmitted`,
`contentType` = `application/json`, `deliveryMode` = persistent; header `eventVersion: 1`;
`traceparent` is added automatically by Micrometer tracing. Spring AMQP also adds its own
`spring_returned_message_correlation` / `spring_listener_return_correlation` headers to match
returns to sends; they're not part of the contract and consumers ignore them.

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
    "submittedAt": "2026-10-16T18:04:11.532Z"
  }
}
```

`occurredAt` is set from the same instant as `data.submittedAt`, so the two are equal today. The
full field reference, the AMQP details and the versioning rules are in
[`events.md`](events.md); the JSON above is the fixture the tests use.

The event carries the minimum the notification needs, and deliberately not the damage
description, phone number or address. The alternative, a "thin" event plus a call back to
the API for details, would couple the services at runtime and need an endpoint that exposes
PII. Compatibility rule: additive changes keep v1; breaking changes publish v2 alongside it.

**RabbitMQ topology:**

| Object | Type | Declared by |
|---|---|---|
| `claims.events` | durable topic exchange | claims-api (the producer owns its exchange) |
| `notification.claim-submitted` | durable queue, binding key `claim.submitted`, DLX = `notification.dlx`, dead-letter routing key `notification.claim-submitted.dlq` | notification-service (the consumer owns its queue) |
| `notification.dlx` → `notification.claim-submitted.dlq` | direct exchange + durable queue, bound with the queue's name | notification-service |

notification-service also declares `claims.events` with the same settings, so it can start
before claims-api has ever run (declaring an existing exchange with matching settings is a
no-op). The explicit dead-letter routing key keeps the DLQ binding independent of the
producer's routing keys.

## 6. notification-service

### Layers

| Package | Contents |
|---|---|
| `messaging` | `ClaimSubmittedListener` (`@RabbitListener`), `ClaimSubmittedEvent` (tolerant-reader records), `UnprocessableEventException`, `DeadLetterRecoverer`, `MessagingConfiguration` (topology, JSON converter, retry rules) |
| `application` | `ClaimConfirmationService`, port `EmailSender`, `ConfirmationEmail` model (policy number already masked) |
| `email` | `SmtpEmailSender` (`JavaMailSender`, multipart text + HTML), `ConfirmationEmailRenderer`, `EmailConfiguration` (Thymeleaf engine with an HTML and a TEXT resolver), `EmailProperties` (`notifications.email.from`) |
| `persistence` | `ProcessedEvent` entity, `ProcessedEventRepository` (`processed_event`: `event_id` PK, `claim_number`, `processed_at`) |

Dependencies: `messaging` → `application` → `persistence`; `email` implements the
`application` port. Checked by an ArchUnit test.

### Processing a message

```
check eventType / eventVersion, validate ── unsupported or invalid? → straight to the DLQ
begin tx
  insert processed_event(event_id)      ── ON CONFLICT DO NOTHING: 0 rows? → already handled: ack and skip
  render email (HTML + text)            ── first name escaped by Thymeleaf
  send via SMTP                         ── throws? → tx rolls back → retry (ADR 0004)
commit → ack
```

- The insert *is* the duplicate check. `INSERT … ON CONFLICT (event_id) DO NOTHING` reports a
  duplicate as "0 rows" without an error. Check-then-insert would let two concurrent
  deliveries both send; insert-and-catch-the-exception would leave a Postgres transaction
  that's aborted and marked rollback-only.
- A concurrent duplicate blocks on the primary key until the first transaction ends. If it
  committed, the duplicate skips; if it rolled back (send failed), the duplicate sends instead.
  An integration test holds one send open to show this.
- Retries happen outside the transaction (each attempt is a new one), so no database
  connection is held during the back-off.
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

How this is configured in Boot 4.1 / Spring AMQP 4.1 (checked in milestone 4):

- The retry interceptor is built on Spring Framework 7's core retry (`org.springframework.core.retry`);
  Spring Retry isn't on the classpath. The properties are still
  `spring.rabbitmq.listener.simple.retry.*`, but **`max-attempts` was replaced by
  `max-retries`** (retries after the first attempt), so 4 attempts is `max-retries: 3`.
  The back-off is `initial-interval` / `multiplier` / `max-interval`.
- Non-retryable exceptions are excluded with a `RabbitListenerRetrySettingsCustomizer` bean
  (`RetryPolicySettings.setExceptionExcludes`). The policy also matches causes, which matters
  because the container wraps everything in a `ListenerExecutionFailedException`. Excluded:
  `AmqpRejectAndDontRequeueException` (the base of `UnprocessableEventException`) and both
  `MessageConversionException` types.
- When the interceptor gives up, Boot calls the `MessageRecoverer` bean. The default
  `RejectAndDontRequeueRecoverer` logs the failure's full stack trace and rethrows it as a cause,
  and exception messages can carry personal data (an SMTP error can quote the recipient's
  address). It doesn't print an event's body: `Message.toString()` only includes JSON bodies up
  to 50 bytes. `DeadLetterRecoverer` replaces it: it increments `notifications.failed`, logs the
  event ID and the exception type only, and throws a cause-less `AmqpRejectAndDontRequeueException`.
- In tests, `application-test.yml` shortens the back-off to 100 ms / 200 ms / 400 ms.

The notification service has its own `notifications` database, so services don't share
tables.

## 7. claims-web

- One page, no router: **form → confirmation panel**. On success the form is replaced with
  "Claim received". It shows the claim number and "We've sent a confirmation to
  jane.doe@example.com — please check your email (and spam folder)", with a "Submit another
  claim" button.
- **Form UX:** grouped `fieldset`s (About you · Your policy · What happened · Property
  address · Declaration), native date input with `min`/`max` from the 365-day window,
  province select, character counter on the description, submit button disabled while a
  request is in flight (prevents double submissions).
- **Errors:** inline field errors on blur and on submit; an error summary at the top that takes
  focus on a failed submit; server-side `400` field errors mapped back onto their inputs;
  network/5xx errors shown as a retryable banner that keeps the entered data.
- **Accessibility:** a `<label>` for every input, `aria-invalid` + `aria-describedby` for
  errors, the error summary and confirmation announced via focus/live region, visible focus
  styles, keyboard-only flow, and colour contrast meeting WCAG AA.
- **Styling:** plain CSS modules, no UI library (small and easy to explain).
- **API client:** a small typed `submitClaim()` wrapper around `fetch`, using relative `/api`
  URLs. Vite's dev-server proxy and nginx in Compose both forward to claims-api. It never
  throws; it returns `success`, `field-errors` (a 400 with an `errors` array) or `failure`
  (network error, 5xx, 429, a 400 with nothing to map, an unreadable body).

How it's built (milestone 5):

- **Form values have the API's shape.** `ClaimFormValues` mirrors the request JSON
  (`policyholder.firstName`, `loss.date`, ...), with every input as text. So the server's
  `field` paths are valid react-hook-form names and `setError(field, ...)` needs no mapping
  table. `toRequest()` converts to the API body (amount to a number, empty optionals left out).
- **One list of fields** (`form/fieldNames.ts`) gives the page order for the error summary and
  decides which server errors have an input. A 400 whose fields aren't in the list shows the
  retry banner instead.
- **Dates are `YYYY-MM-DD` strings,** and "today" comes from `Intl` in `America/Winnipeg`, like
  the server's `LossDateRule` (tested at 10 pm Winnipeg time, when UTC is already tomorrow).
- **Validation timing:** `mode: 'onBlur'`, so a field is checked when left and everything on
  submit; after a submit, fields re-check as they change. The summary appears only after a
  submit attempt, and takes focus once per attempt (`submitCount`) so a blur error never steals
  focus. react-hook-form's own focus-the-first-invalid-input is off (`shouldFocusError: false`);
  left on, it won and the summary never got focus in a real browser.
- **After success the form is unmounted,** so "Submit another claim" brings back a fresh form
  without a manual reset.
- **Server-side gaps the client works around:** the server rejects an empty-string phone with
  the phone-format message and doesn't trim before validating, so the client trims and
  omits empty optionals.
- **Not done:** dark mode, translations (French), a polite live region for field errors beyond
  the focus-moved summary.

## 7a. Containers (milestone 6)

`docker compose up --build -d --wait` builds and runs everything.

- **Java images** (`claims-api/Dockerfile`, `notification-service/Dockerfile`): two stages.
  The build stage (`eclipse-temurin:25-jdk`) runs `mvnw dependency:go-offline` in its own layer,
  then `package -DskipTests` (CI runs the tests), then `java -Djarmode=tools -jar app.jar
  extract --layers`, the Boot 3.3+/4 syntax. The runtime stage copies the four layers
  (dependencies, loader, snapshot dependencies, application) so a code-only change rebuilds one
  small layer. It runs as a fixed non-root user (uid 10001) with
  `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`, and compose sets `mem_limit: 768m`
  so the heap is sized from the container, not the host. `java` is PID 1 (exec-form
  `ENTRYPOINT`), so it gets SIGTERM and shuts down gracefully.
- **Deviation: Alpine JRE.** The runtime image is `eclipse-temurin:25-jre-alpine`, not
  `25-jre`. The Ubuntu JRE image has neither curl nor wget, so the readiness healthcheck would
  have needed an extra package (more surface, more size). The Alpine image has BusyBox `wget`
  and is about 175 MB smaller. Build stays on the Ubuntu JDK image.
- **Web image:** `node:24-alpine` runs `npm ci` and `npm run build`; the result is served by
  `nginxinc/nginx-unprivileged:1.30-alpine` (uid 101) on port 8080, published as 3000.
- **nginx.conf** (replaces `conf.d/default.conf`): SPA fallback to `index.html`; `/assets/`
  (hashed file names) cached for a year and `index.html` revalidated (`expires`, not
  `add_header`, so the security headers aren't dropped by location-level inheritance);
  `/api/` proxied to `claims-api:8080` with `X-Forwarded-*` headers; `/actuator` answers 404
  (otherwise the SPA fallback would answer it with `index.html`); `client_max_body_size 16k`;
  `limit_req` 5 requests/s per IP with a burst of 10, returning 429; gzip; `server_tokens off`;
  CSP (`default-src 'self'`, no inline script or style, `frame-ancestors 'none'`, `object-src
  'none'`), `nosniff`, `Referrer-Policy`, `X-Frame-Options`, `Permissions-Policy`. HSTS is left
  to the TLS edge in a real deployment.
- **Upstream DNS:** `proxy_pass` uses a variable with Docker's resolver (`127.0.0.11`), so
  nginx looks up `claims-api` per request. It then survives the API container being recreated,
  and can start before the API is up.
- **Compose:** each app gets its settings from environment variables (`DB_HOST=postgres`,
  `RABBITMQ_HOST=rabbitmq`, `SMTP_HOST=mailpit`) and its secrets from `.env` through `${VAR:?}`.
  `depends_on: condition: service_healthy` orders startup; healthchecks use
  `/actuator/health/readiness` (and nginx's own `/healthz` for the web container, on
  `127.0.0.1` because BusyBox `wget` tries IPv6 first for `localhost` and nginx listens on
  IPv4 only). Healthchecks live in compose rather than the Dockerfiles so the whole startup
  contract is in one file. `restart: unless-stopped` on the apps.

## 8. Security and customer data

| Area | Measure |
|---|---|
| Secrets | None in the repo. Compose reads `${VAR}` from `.env` (git-ignored); `.env.example` has obviously local-only values. Each service gets its own DB user with access only to its own database |
| PII in logs | Log claim number, event ID and trace ID only. Request/event records override `toString()` so names and emails can't leak through an accidental `log.info("{}", request)`. A test asserts this |
| PII in events/email | Only the fields needed (section 5). Policy number masked in the email |
| Input | Validation + length limits on every field; JPA parameter binding; nginx `client_max_body_size 16k` |
| Output | React and Thymeleaf escape by default. `ProblemDetail` responses never include stack traces |
| Abuse | nginx `limit_req` on `/api` (per-IP rate limit) |
| Headers | nginx sets CSP (including `frame-ancestors 'none'`), `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, `Permissions-Policy` |
| Containers | JRE-only / alpine images, non-root users (uid 10001 for the Java apps, nginx uid 101), JVM sized from the container memory limit, actuator not routed through nginx (404), `.dockerignore` keeps `.env` and build output out of images |
| Supply chain | Dependabot for Maven, npm, Docker images and Actions |
| Production notes | Customer authentication, TLS everywhere, secrets manager, encryption at rest, data retention policy, CAPTCHA, policy verification |

## 9. Observability

- **Health:** Actuator liveness/readiness, including DB and RabbitMQ indicators. Compose
  `healthcheck`s plus `depends_on: condition: service_healthy`, so services start in order.
- **Structured logs:** Spring Boot's built-in structured logging. Compose sets
  `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, so the containers write one JSON object per line in
  the Elastic Common Schema; IDE and test runs keep Spring's readable format. ECS rather than
  the Logstash format because it's a documented, vendor-neutral schema (`service.name`,
  `log.level`, `error.stack_trace`, ...) that most log tools understand, and it's being merged
  into OpenTelemetry's semantic conventions. Every line written during a traced request or
  message has `traceId` and `spanId`. The PII rule (section 8) still holds: messages contain
  only claim numbers, event IDs and trace IDs.
- **Tracing:** `spring-boot-starter-opentelemetry` (Micrometer Tracing with the
  OpenTelemetry bridge, W3C `traceparent` propagation). Sampling is 100% locally
  (`management.tracing.sampling.probability: 1.0`). RabbitMQ observations are on for the
  producer's `RabbitTemplate` (`spring.rabbitmq.template.observation-enabled`) and the
  consumer's listener container (`spring.rabbitmq.listener.simple.observation-enabled`): the
  template adds a `traceparent` header to each message and the listener continues that trace.
  So **one trace ID follows a claim from the HTTP request, through the outbox and the broker,
  into the notification-service's email send and logs.** The SMTP send has its own span
  (a span named `send confirmation email`, from an observation called `notification.email.send` that is also a timer metric).
- **Through the outbox:** the relay publishes on a scheduler thread, outside the request, so
  the trace context is saved with the event and restored when it's published
  (`OutboxTracing`):
  1. `JpaEventOutbox.append` runs inside the request's span. It writes that context as a W3C
     `traceparent` (`00-<trace ID>-<span ID>-01`) into `outbox_event.trace_parent`, in the
     same transaction as the claim.
  2. For each row, `OutboxRelay` starts an `outbox publish` span whose parent is the saved
     context, and makes it current while it sends. The `RabbitTemplate`'s send span is a child
     of it, and the message's `traceparent` header carries the original trace ID.
  3. A row without `trace_parent` (written outside a request) still publishes, in a new trace.

  Both directions use Micrometer's `Propagator`, the same W3C code that handles the HTTP and
  AMQP headers. Spring's observation of `@Scheduled` runs is switched off in claims-api
  (`management.observations.enable.tasks.scheduled.execution: false`); otherwise every
  500 ms relay poll would start a new, empty trace. For the same reason both services skip
  observing `/actuator` requests (an `ObservationPredicate` in `ObservabilityConfiguration`),
  so the Compose health checks don't add a trace every 10 seconds.
- **Trace ID for callers:** every claims-api response has an `X-Trace-Id` header, and a 500
  `ProblemDetail` includes `traceId`, which leads straight to the error's log lines.
- **Trace viewer:** Compose runs Jaeger v2 (all-in-one, in-memory) at http://localhost:16686.
  The apps export spans over OTLP/HTTP only when
  `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` is set, which only Compose does, so
  tests and IDE runs neither need Jaeger nor log export errors.
- **Metrics:** `/actuator/prometheus` on both services: counters `claims.submitted`,
  `notifications.sent`, `notifications.failed` (events dead-lettered),
  `notifications.duplicates_skipped`, the gauge `outbox.pending`, the
  `notification.email.send` timer, and Spring's HTTP and RabbitMQ timers. OTLP metrics push
  (on by default with the OpenTelemetry starter) is switched off: Prometheus scrapes instead.
  *(stretch: Prometheus + Grafana in Compose)*
- **Tests:** Spring Boot tests switch span export off, and with it W3C propagation, so the
  tracing tests use `@AutoConfigureTracing`. claims-api checks that the outbox row and the
  published message are in the request's trace, that a caller's `traceparent` is joined, and
  that a row without trace context publishes in a new trace; notification-service checks
  that the email is sent, and logged, in the trace from the message's header.
- **Production notes:** lower the sampling rate (the trace ID is still in every log line
  when a trace isn't sampled); send spans to a collector rather than straight to a backend;
  strip or re-validate an incoming `traceparent` at the edge (nginx) so the public can't
  choose trace IDs.

## 10. Testing strategy

TDD for domain rules and the two use-case services; test-after for framework wiring.

| Level | Where | Most important cases |
|---|---|---|
| Unit (domain) | claims-api | each validation rule's boundaries (loss date today / tomorrow / 365 / 366 days ago); claim number format; postal code / policy normalization |
| Unit (application) | claims-api | `submit` saves the claim **and** appends a matching outbox event; nothing is appended if saving fails |
| Web slice | claims-api `@WebMvcTest` | 201 + body on valid input; 400 `ProblemDetail` with field paths; malformed JSON → 400; no PII in logs |
| Integration | claims-api + Testcontainers (Postgres, RabbitMQ) | submit → row in `claim` + `outbox_event` → relay publishes → message on a test queue with the right properties; broker down (container paused) → row stays pending, then publishes after recovery; nothing bound → returned, stays pending |
| Contract (producer) | claims-api | published body matches `src/test/resources/contracts/claim-submitted-v1.json`; the notification service's tests parse a copy of that file (ADR 0005) |
| Unit | notification-service | email contains the claim number and first name; masked policy; first name HTML-escaped; duplicate event skipped; SMTP failure propagates (for retry); unknown version → non-retryable; retry policy classification; recoverer; no PII in `toString()` |
| Integration | notification-service + Testcontainers (Postgres, RabbitMQ, Mailpit) | publish event → email in Mailpit containing the claim number; same event twice → one email; concurrent duplicate waits on the primary key, then skips; unknown version / non-JSON body → DLQ without retries; SMTP down → 4 attempts → DLQ, nothing recorded; no PII in the logs; `/actuator/prometheus` has the counters |
| Architecture | both, ArchUnit | layer dependency rules; `domain` has no Spring/JPA imports |
| Component | claims-web, Vitest + RTL + MSW | required-field errors with the summary focused; date-in-future, bad policy number, short description and bad postal code; successful submit shows the claim number and focuses the confirmation, and sends the API's JSON shape; the button is disabled while pending; server 400 maps to fields; 500 and network errors show the banner and keep the data (and Try again works) |
| Unit | claims-web | zod schema boundaries (365/366 days, 19/20 and 2000/2001 characters, amount limits, postal and policy formats, all 13 provinces); Winnipeg "today"; `submitClaim()` outcomes |
| End-to-end | e2e/, Playwright vs Compose | fill the form in a real browser → confirmation shows a claim number → Mailpit API has an email to that address containing **the same** claim number; empty submit shows the errors and makes no API call; axe finds no serious/critical violations on the form, the error state and the confirmation |

The end-to-end test is the automated version of the interview demo.

## 11. CI/CD (GitHub Actions)

- `claims-api` and `notification-service` jobs run `./mvnw verify` (unit + Testcontainers tests;
  Docker is available on `ubuntu-latest`).
- `claims-web` job: `npm ci`, lint, type-check, test, build.
- `e2e` job (after the above): `docker compose up --build --wait`, Playwright, upload the HTML
  report and Compose logs as artifacts if it fails.
- Maven/npm caching; runs on pushes and PRs; read-only token, superseded runs are cancelled.
- Dependabot: weekly, minor and patch updates grouped per ecosystem (Maven, npm, Docker,
  Compose, Actions). Major versions are ignored for everything except GitHub Actions: they
  need a deliberate upgrade, not a bot PR. (The first round of major-version PRs was reviewed and
  rejected; the Postgres 18 one failed CI, and that image also changes the data directory layout.)
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
| 7 | **Observability** — structured logs, tracing across the broker (through the outbox), Jaeger | One trace ID visible in both services' logs for a submission |
| 8 | **E2E + CI** — Playwright suite, GitHub Actions, Dependabot | Pipeline green on GitHub |
| 9 | **Docs** — README (prerequisites, run, test, demo script, architecture, trade-offs, production improvements, AI usage statement), finished ADRs, `docs/events.md` | A fresh clone works by following the README alone |
| 10 | *(stretch)* Prometheus/Grafana, API `Idempotency-Key`, GHCR images, free-tier cloud deployment | — |

**Status:** milestones 1–9 are done. Milestone 10 was not started; the stretch items are listed
under "What I'd add for production" in the README instead.

## 13. Interview prep

- **Demo script:** it's written out, step by step and checked against the running stack, in the
  README's [demo section](../README.md#demo-script). In short: validation errors → submit →
  confirmation → email in Mailpit → RabbitMQ UI → one trace in Jaeger and its trace ID in the
  logs → stop RabbitMQ, submit, start it again → stop Mailpit, submit, watch the retries, find
  the message in the DLQ, replay it.
- **Likely questions:** why the outbox; what happens if X is down (DB, broker, SMTP, consumer);
  how this scales (more consumers on the queue, relay with `SKIP LOCKED`, partitioning); what's
  missing for production (section 8 notes, auth, monitoring/alerting, a DLQ runbook). The
  README's "Key design decisions" and "What I'd add for production" sections hold the short
  answers.

## 14. Schedule and decisions log

- **Deadline: Sunday 25 Oct 2026.** Target: milestones 1–9 done by Thu 22 Oct, leaving
  Fri–Sat for stretch items, a dry-run of the demo and interview prep.
- **Branching:** work directly on `Dev` (the default branch), one commit per milestone or
  smaller.
- **Plan accepted** as written on 17 Oct 2026.
- Cloud deployment: not done. The stack runs locally with Compose.
