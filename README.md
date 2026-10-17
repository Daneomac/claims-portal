# Claims Portal

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

Useful commands:

```bash
docker compose logs -f claims-api notification-service   # follow the application logs
docker compose down                                      # stop; data volumes are kept
docker compose down -v                                   # stop and delete the database and broker data
```

To run only the supporting services and start the apps from your IDE or `mvnw spring-boot:run`,
use `docker compose up -d --wait postgres rabbitmq mailpit`; the apps' defaults point at
`localhost`, and `npm run dev` in `claims-web` proxies `/api` to `localhost:8080`.

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
| `claims-web/` | React + TypeScript claim form, plus `nginx.conf` for the container |
| `infra/` | Supporting configuration (Postgres init script) |
| `docs/` | Plan and architecture decision records |
