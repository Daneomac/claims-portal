# Claims Portal — Architecture & Build Plan

Working plan for the Red River Mutual Software Developer III challenge. This becomes the
basis for the README's architecture section once the build is done.

## 1. Goal

A small, polished vertical slice: a customer submits a home insurance claim in a web form,
the API validates and stores it with a unique claim number, publishes a `ClaimSubmitted`
event, and a **separate** notification service consumes the event and emails the customer
their claim number.

## 2. Technology choices

| Concern | Choice | Why |
|---|---|---|
| Language / runtime | Java 25 (LTS) | Matches the employer's stack; already installed |
| Backend framework | Spring Boot 4.x, Maven wrapper (`mvnw`) | Preferred stack; wrapper means no global Maven install |
| Frontend | React + TypeScript + Vite | "JavaScript framework" requirement; fast dev loop, typed API contract |
| Database | PostgreSQL (container) + Flyway migrations | Real persistence and schema versioning; H2 only in unit tests |
| Message broker | RabbitMQ (container) | Simple to run, first-class Spring support, built-in dead-letter exchanges, management UI is good for the demo |
| Email | Mailpit (container, SMTP + web inbox) | No-cost, no credentials, emails viewable at `localhost:8025` during the demo |
| Containers | Docker Compose | One command brings up everything |
| CI | GitHub Actions | Build + test both services and the frontend on every push |

**Alternatives considered (talking points):** Kafka (overkill for a single event type; heavier
to run), Spring Modulith events (same process — violates the "separate subscriber service"
requirement), Angular (fine, but more boilerplate for one form).

## 3. Architecture

```
 ┌──────────────┐  POST /api/claims   ┌──────────────────────┐       ┌────────────┐
 │  claims-web  │ ──────────────────▶ │      claims-api      │──────▶│ PostgreSQL │
 │ React (Vite) │ ◀── 201 + claimNo ─ │  validate → save →   │       │ claims,    │
 └──────────────┘                     │  outbox → publish    │       │ outbox     │
                                      └──────────┬───────────┘       └────────────┘
                                                 │ ClaimSubmitted (JSON)
                                                 ▼
                                   ┌──────────────────────────┐
                                   │ RabbitMQ                  │
                                   │ exchange: claims.events   │
                                   │ queue: notification.claim-│
                                   │        submitted (+ DLQ)  │
                                   └──────────┬───────────────┘
                                              ▼
                                ┌────────────────────────────┐      ┌─────────┐
                                │   notification-service     │─SMTP▶│ Mailpit │
                                │ consume → dedupe → render  │      └─────────┘
                                │ template → send email      │
                                └────────────────────────────┘
```

### Repository layout

```
claims-portal/
├── claims-api/              Spring Boot — REST, validation, persistence, event publishing
├── notification-service/    Spring Boot — event consumer, email rendering & sending
├── claims-web/              React + TS — claim form and confirmation page
├── docs/                    Plan, architecture notes, ADRs
├── docker-compose.yml       postgres, rabbitmq, mailpit, both services, web
├── .github/workflows/ci.yml
└── README.md
```

The two services are **independent Maven projects** (each with its own `mvnw`), not modules
sharing code. The `ClaimSubmitted` event contract is duplicated as a small record in each
service and documented in `docs/events.md`. Trade-off: a little duplication in exchange for
services that build, deploy and version independently — the usual choice for event-driven
services. (A shared contract jar is the alternative; worth discussing in the review.)

### claims-api layering

- `web` — `ClaimController`, request/response DTOs, `@RestControllerAdvice` returning RFC 9457
  `ProblemDetail` for validation and server errors.
- `application` — `ClaimSubmissionService` (use case orchestration), `ClaimNumberGenerator`
  interface.
- `domain` — `Claim` entity, `LossType` enum, value rules (e.g. loss date not in the future).
- `infrastructure` — JPA repositories, `OutboxRelay` (polls outbox and publishes to RabbitMQ),
  `ClaimEventPublisher` interface + RabbitMQ implementation.

**Claim number:** `HC-2026-000123` style — prefix + year + zero-padded value from a Postgres
sequence. Unique by construction, human-readable, and hidden behind an interface so it can be
swapped (e.g. for a ULID).

**Transactional outbox:** the claim row and an outbox row are written in the same DB
transaction; a scheduled relay publishes pending outbox rows and marks them sent. This avoids
the "saved but never published" (or "published but rolled back") dual-write problem. It is the
key reliability decision to explain in the review.

### notification-service layering

- `messaging` — `@RabbitListener` that deserializes `ClaimSubmitted` and hands off to the use case.
- `application` — `ClaimConfirmationService`: check idempotency, build the message, send.
- `email` — `EmailSender` interface; `SmtpEmailSender` (Spring `JavaMailSender`); Thymeleaf
  HTML + plain-text templates.
- `persistence` — `processed_events` table (event ID primary key) for duplicate-event
  protection. Uses its own schema in the same Postgres container.

**Failure handling:** limited retries with exponential back-off, then the message goes to a
dead-letter queue (`notification.claim-submitted.dlq`) instead of being lost or retried forever.

## 4. Claim form fields & validation

| Field | Rules (enforced client-side **and** server-side) |
|---|---|
| First / last name | required, ≤ 100 chars |
| Email | required, valid email |
| Phone | optional, valid phone pattern |
| Policy number | required, format e.g. `RRM-` + 8 digits |
| Date of loss | required, not in the future, not more than 1 year ago |
| Type of loss | required, enum: Fire, Water, Wind/Hail, Theft, Vandalism, Other |
| Property address | street, city, province (CA list), postal code (Canadian format) |
| Description | required, 20–2000 chars |
| Estimated loss amount | optional, positive, 2 decimals |

Server-side validation is authoritative (Jakarta Bean Validation); the client duplicates it
only for UX.

## 5. Security & data handling

- No secrets in the repo: config via environment variables; `.env.example` with local dummy
  values only; `.env` git-ignored.
- PII-aware logging: log claim number and event ID, never the email, name, or description.
- Input validation and size limits on every field; parameterized queries via JPA.
- CORS restricted to the web origin; containers run as non-root.
- Production notes for the README: auth/customer identity, TLS, secrets manager, data
  retention, rate limiting.

## 6. Testing strategy

| Layer | Tooling | Key cases |
|---|---|---|
| Domain / service unit tests | JUnit 5, Mockito, AssertJ | validation rules, claim number format, service saves claim **and** outbox row, publisher called with correct payload |
| Web slice | `@WebMvcTest` | 201 + claim number on valid input; 400 `ProblemDetail` with field errors on invalid input |
| Consumer unit tests | JUnit 5, Mockito | email contains claim number; duplicate event is skipped; send failure propagates for retry |
| Integration | Testcontainers (Postgres, RabbitMQ, Mailpit) | end-to-end: submit → event → email arrives with claim number |
| Frontend | Vitest + React Testing Library | field validation messages, successful submit shows confirmation, server errors shown |

Approach: write tests first for the domain/service logic (TDD), test-after for wiring.

## 7. Observability

Spring Boot Actuator health/readiness endpoints (used by Compose health checks), structured
JSON logs with a correlation ID carried from the HTTP request into the event headers, and
Micrometer counters (`claims.submitted`, `notifications.sent`, `notifications.failed`).

## 8. Build order (milestones)

Each milestone ends in a working, committed state.

1. **Scaffold** — repo skeleton, `.gitignore`, `.editorconfig`, Compose with Postgres,
   RabbitMQ, Mailpit; generate both Spring Boot projects and the Vite app.
2. **claims-api core** — domain, validation, claim number, persistence, REST endpoint, tests.
3. **Event publishing** — outbox table and relay, RabbitMQ topology, tests.
4. **notification-service** — consumer, idempotency, email templates, retry + DLQ, tests.
5. **claims-web** — form, client validation, confirmation page, accessibility pass, tests.
6. **Containerize** — Dockerfiles for all three apps; `docker compose up` runs everything.
7. **End-to-end test** — Testcontainers test across the real flow.
8. **CI** — GitHub Actions workflow.
9. **Docs** — README (prereqs, run, test, demo script), architecture, AI-usage statement.
10. *(Optional)* free-tier cloud deployment.

## 9. Open questions

- Shared event contract jar vs. duplicated record (leaning duplicated).
- Cloud deployment target, if attempted (e.g. Render / Fly.io free tier, or skip).
