# Gyro API

The Gyro API is a Kotlin and Spring Boot application backed by PostgreSQL and Redis. This directory owns everything
required to build, test, and run the backend without files from the parent repository.

The application source is licensed under [AGPL-3.0-only](LICENSE). Read [the contribution guide](CONTRIBUTING.md)
before proposing changes. The [trademark notice](TRADEMARKS.md) separates the software license from permission to
present a fork as the official Gyro service.

## Prerequisites

- Java 25 toolchain
- Docker with Compose

## Run locally

Create the untracked environment file and start PostgreSQL and Redis:

```bash
cp .env.example .env
docker compose up -d
```

Load the local environment and start Spring Boot:

```bash
set -a
source .env
set +a
./gradlew bootRun
```

The API listens on `http://localhost:8080`. OpenAPI JSON is available at `http://localhost:8080/api-docs`, and Swagger
UI is available at `http://localhost:8080/swagger-ui` in local development.

Stop local infrastructure without deleting its PostgreSQL volume:

```bash
docker compose stop
```

After the API has started once and Flyway has created the schema, optionally add a few clearly synthetic foods to the
local Compose database:

```bash
./scripts/seed-contributor-demo.sh
```

The fixture is idempotent and contains no user account, private data, or production catalog rows. Register a local
account using the log-only verification codes, then search for `Example Oat Bowl` or `Example Lentil Soup`. Do not use
the fixture as nutritional advice. The script only targets the named contributor database in this local Compose stack.

## Validate changes

Run the immutable-migration check, tests, application build, and container build:

```bash
./scripts/verify-flyway-migration-immutability.sh
./gradlew --no-daemon test
./gradlew --no-daemon verifyOpenApi
./gradlew --no-daemon bootJar
docker build -t gyro-api:local .
```

Integration tests use Testcontainers and require Docker.

## API contract

`openapi/openapi.json` is the canonical, versioned HTTP contract. After changing a controller or DTO, run
`./gradlew generateOpenApi`, review the artifact diff, and rerun `./gradlew verifyOpenApi`. The regular test suite also
checks that the committed artifact matches the application. See [the API versioning policy](docs/api-versioning.md)
before changing a published operation. The public CI template is checked in under `.github/workflows/` but remains
disabled while ADR 0006 is in force.

## Local provider behavior

The checked-in example configuration cannot contact production providers:

| Capability | Local behavior |
| --- | --- |
| Signup and login verification | `log-only` adapters emit structured development events |
| Billing | PayPing is disabled; tests use `FakeBillingProvider`, with an opt-in browser simulator for `dev` |
| Product email and SMS notifications | Disabled |
| Web Push | Disabled until a local VAPID key pair is supplied |
| Telegram delivery and account linking | Disabled |

Do not enable a real provider with production credentials in this repository. Keep local secrets in the ignored `.env`
file and use the private operations repository for deployed configuration.

The example database account and password are disposable local values, not deployed credentials. The example omits
`JWT_SECRET` and `VERIFICATION_CODE_PEPPER`; the `dev` profile supplies public, local-only fallbacks so the copied file
runs immediately. Generate a fresh Base64 key of at least 32 bytes and configure it outside the repository for any
shared environment. Keep the frontend's JWT issuer and audience equal to the API example values.

By default, `FakeBillingProvider` returns a placeholder `pay.example.com` URL for automated tests; it never charges a
card or calls PayPing. To test the browser payment journey locally, set `BILLING_DEMO_ENABLED=true` in your ignored
`.env` and restart the API. With the `dev` profile and `PAYPING_ENABLED=false`, checkout instead opens a local page
with success, failure, and pending choices. It uses a per-attempt opaque token and redirects through the normal
callback and verification path, so the invoice and subscription transitions are exercised without a real payment.
The simulator is disabled by default, absent in `prod`, and unavailable when PayPing is enabled. Its in-memory checkout
state resets when the API restarts; create a new checkout after restarting.

The production adapter is `PayPingBillingProvider` in this source tree. It is selected when `PAYPING_ENABLED=true` and
requires a separately supplied `PAYPING_API_KEY`. In `prod`, if PayPing is off, `DisabledBillingProvider` rejects
checkout; the fake is never selected. Provider credentials and production return URLs belong to private deployment
configuration, not either public repository. Publishing this backend will publish the PayPing integration code, but
not its credentials or operational configuration.

## Database migrations

Flyway migrations live under `src/main/resources/db/migration`. Never edit a migration that exists on `main`; create a
new versioned migration. The verification script works both in this private monorepo and when `api/` becomes the root
of the standalone public repository.
