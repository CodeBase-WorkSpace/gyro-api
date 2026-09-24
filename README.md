# Gyro API

Gyro API is the Kotlin and Spring Boot backend for Gyro's nutrition and health-tracking application. It provides the
versioned HTTP API, authentication, food and meal data, goals, progress, notifications, subscriptions, and billing
workflows used by [Gyro Web](https://github.com/CodeBase-WorkSpace/gyro-web).

This repository is standalone: it includes the application source, PostgreSQL migrations, Redis-backed services,
OpenAPI contract, tests, local Compose setup, and synthetic contributor fixtures.

## Stack

- Kotlin and Spring Boot
- PostgreSQL with Flyway migrations
- Redis for selected runtime state and coordination
- Gradle build and Testcontainers integration tests
- OpenAPI contract at `openapi/openapi.json`

The application source is licensed under [AGPL-3.0-only](LICENSE). The Gyro name and service identity are governed by
the separate [trademark notice](TRADEMARKS.md). Production configuration and credentials remain outside this
repository.

## Quick start

Prerequisites: Java 25 and Docker with Compose.

```bash
cp .env.example .env
docker compose up -d

set -a
source .env
set +a
./gradlew bootRun
```

The API listens on `http://localhost:8080`. In local development, OpenAPI JSON is available at
`http://localhost:8080/api-docs` and Swagger UI at `http://localhost:8080/swagger-ui`.

After Flyway creates the schema, load synthetic foods for a contributor journey:

```bash
./scripts/seed-contributor-demo.sh
```

The fixture is idempotent and inserts only `Example Oat Bowl` and `Example Lentil Soup`. It contains no user account,
production data, or production catalog rows. Register a fresh local account through the web app using the log-only
verification flow, then search for either example food.

Stop local infrastructure without deleting its database volume:

```bash
docker compose stop
```

## Local billing simulation

PayPing is disabled in the contributor environment. Tests use `FakeBillingProvider`, and the development profile has
an opt-in browser simulator:

```bash
# Add this to the ignored .env file, then restart the API.
BILLING_DEMO_ENABLED=true
```

The simulator offers success, failure, and pending outcomes and follows the normal callback and verification path. It
never charges a card or contacts PayPing. Its in-memory state resets when the API restarts.

Production uses `PayPingBillingProvider` only when private deployment configuration enables it and supplies
`PAYPING_API_KEY`. Never place provider credentials or production return URLs in this repository.

## Validate a change

```bash
./scripts/verify-flyway-migration-immutability.sh
./gradlew --no-daemon test
./gradlew --no-daemon verifyOpenApi
./gradlew --no-daemon bootJar
docker build -t gyro-api:local .
```

Integration tests use Testcontainers and require Docker. Never edit a migration that already exists on `main`; add a
new versioned migration instead.

When a controller or DTO changes, run `./gradlew generateOpenApi`, review the contract diff, and rerun
`./gradlew verifyOpenApi`. Coordinate any breaking or incompatible change with the frontend's pinned contract.

## Provider behavior in local development

The contributor configuration keeps outbound services safe by default:

| Capability | Local behavior |
| --- | --- |
| Signup and login verification | Structured log-only adapters |
| Billing | Fake provider in tests; opt-in browser simulator |
| Product email and SMS | Disabled |
| Web Push | Disabled until a local VAPID key pair is supplied |
| Telegram delivery and linking | Disabled |

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a change. For security reports, follow [SECURITY.md](SECURITY.md)
instead of publishing exploit details in an issue.
