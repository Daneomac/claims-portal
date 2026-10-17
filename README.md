# Claims Portal

Home insurance claim submission portal: a React form, a Spring Boot API that validates and
stores claims, and a separate notification service that consumes a `ClaimSubmitted` event
from RabbitMQ and emails the customer their claim number.

> Work in progress. The plan and design decisions are in [docs/PLAN.md](docs/PLAN.md) and
> [docs/adr/](docs/adr/README.md). Full setup and demo instructions will be added in
> milestone 9.

## Prerequisites

- Java 25 (JDK)
- Node.js 24+
- Docker Desktop (or another Docker engine with Compose v2+)

Maven isn't required: each service includes the Maven wrapper (`mvnw`).

## Quick start (current state)

```bash
cp .env.example .env            # local-only values; .env is git-ignored
docker compose up -d --wait     # PostgreSQL, RabbitMQ, Mailpit
```

| Component | URL |
|---|---|
| RabbitMQ management | http://localhost:15672 (user/password from `.env`) |
| Mailpit inbox | http://localhost:8025 |

## Running the tests

```bash
cd claims-api && ./mvnw verify            # needs Docker (Testcontainers)
cd notification-service && ./mvnw verify  # needs Docker (Testcontainers)
cd claims-web && npm ci && npm test
```

## Repository layout

| Path | What |
|---|---|
| `claims-api/` | Spring Boot claim submission API |
| `notification-service/` | Spring Boot event consumer that sends confirmation emails |
| `claims-web/` | React + TypeScript claim form |
| `infra/` | Supporting configuration (Postgres init script) |
| `docs/` | Plan and architecture decision records |
