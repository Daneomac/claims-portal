# Claims Portal

[![CI](https://github.com/Daneomac/claims-portal/actions/workflows/ci.yml/badge.svg?branch=Dev)](https://github.com/Daneomac/claims-portal/actions/workflows/ci.yml)

Home insurance claim submission portal: a React form, a Spring Boot API that validates and
stores claims, and a separate notification service that consumes a `ClaimSubmitted` event
from RabbitMQ and emails the customer their claim number.

> Work in progress. The plan and design decisions are in [docs/PLAN.md](docs/PLAN.md) and
> [docs/adr/](docs/adr/README.md). Full demo instructions will be added in
> milestone 9.

## Prerequisites

- **To run the stack:** Docker Desktop (or another Docker engine with Compose v2+). Nothing else:
  the images build the Java and Node apps themselves.
- **To run the tests or develop outside containers:** Java 25 (JDK) and Node.js 24+. Maven isn't
  required: each service includes the Maven wrapper (`mvnw`).

## Quick start

```bash
cp .env.example .env                 # local-only values; .env is git-ignored
docker compose up --build -d --wait  # builds the three apps, starts everything, waits until healthy
```

The first build downloads dependencies and takes a few minutes; later builds reuse cached
layers. Then open the claim form at http://localhost:3000, submit a claim, and the confirmation
email appears in the Mailpit inbox at http://localhost:8025.

| Component | URL | Notes |
|---|---|---|
| Claim form (claims-web) | http://localhost:3000 | nginx serves the SPA and proxies `/api` to the API |
| claims-api | http://localhost:8080 | also exposed directly for curl demos: `POST /api/v1/claims` |
| notification-service | http://localhost:8081/actuator/health | actuator only, no business endpoints |
| Mailpit inbox | http://localhost:8025 | every email the stack sends lands here |
| RabbitMQ management | http://localhost:15672 | user and password from `.env` |
| Jaeger (traces) | http://localhost:16686 | one trace per submission, across both services |

Useful commands:

```bash
docker compose logs -f claims-api notification-service   # follow the application logs
docker compose down                                      # stop; data volumes are kept
docker compose down -v                                   # stop and delete the database and broker data
```

To run only the supporting services and start the apps from your IDE or `mvnw spring-boot:run`,
use `docker compose up -d --wait postgres rabbitmq mailpit`; the apps' defaults point at
`localhost`, and `npm run dev` in `claims-web` proxies `/api` to `localhost:8080`.

## Seeing a trace

Every claim submission is one trace, from the HTTP request through the outbox and RabbitMQ to
the confirmation email (details in [PLAN section 9](docs/PLAN.md#9-observability)).

1. Submit a claim (the form, or `POST /api/v1/claims`). Every claims-api response has an
   `X-Trace-Id` header (visible in the browser's network tab or with `curl -i`).
2. Find the log lines for it in both services. The containers log JSON (ECS), and every
   line written for that claim has the same `traceId`:

   ```bash
   docker compose logs claims-api notification-service | grep <trace id>
   ```

3. Open Jaeger at http://localhost:16686, pick the `claims-api` service and the
   `http post /api/v1/claims` operation (or paste the trace ID into the search box). The trace
   shows the request, the `outbox publish` span about half a second later, the RabbitMQ send
   and receive, and the email send in notification-service.

Jaeger keeps traces in memory, so they're gone after `docker compose down`. Tests and IDE runs
don't export spans; the trace IDs are still in their log lines.

## Running the tests

```bash
cd claims-api && ./mvnw verify            # needs Docker (Testcontainers)
cd notification-service && ./mvnw verify  # needs Docker (Testcontainers)
cd claims-web && npm ci && npm test
```

### End-to-end tests

The Playwright suite in `e2e/` drives the real stack in Chromium: it fills in and submits the
claim form, checks the confirmation, then polls Mailpit's API until the email arrives and checks
that it carries the same claim number. It also checks the validation errors (and that no API
call is made) and runs axe accessibility scans on the form and the confirmation.

```bash
docker compose up --build -d --wait   # the stack must be running (see Quick start)
cd e2e
npm ci
npx playwright install chromium      # first time only (add --with-deps on a fresh Linux machine)
npm test
npm run report                        # open the HTML report (traces and screenshots of failures)
```

The tests expect the form at http://localhost:3000 and Mailpit at http://localhost:8025. To test
another deployment, set `BASE_URL` and `MAILPIT_URL`.

### Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every push and pull request. The
two Java services (`./mvnw -B verify`, Testcontainers included) and claims-web (type-check, lint,
test, build) are checked in parallel; if all three pass, the `e2e` job starts the Compose stack
and runs the Playwright suite. When something fails, the Playwright report and the Compose logs
are attached to the run as artifacts. Dependabot opens weekly update PRs for Maven, npm, Docker
and GitHub Actions ([`.github/dependabot.yml`](.github/dependabot.yml)).

## Repository layout

| Path | What |
|---|---|
| `claims-api/` | Spring Boot claim submission API |
| `notification-service/` | Spring Boot event consumer that sends confirmation emails |
| `claims-web/` | React + TypeScript claim form, plus `nginx.conf` for the container |
| `e2e/` | Playwright end-to-end tests that run against the Compose stack |
| `.github/` | CI workflow and Dependabot configuration |
| `infra/` | Supporting configuration (Postgres init script) |
| `docs/` | Plan and architecture decision records |
