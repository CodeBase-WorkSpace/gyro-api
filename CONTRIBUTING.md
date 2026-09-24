# Contributing to Gyro API

Thanks for working on the API. This repository is prepared for an open-source release but is not yet accepting outside contributions. The maintainers will publish the contribution process when the repository becomes public.

## Local setup

Follow [the API README](README.md) to start the local PostgreSQL and Redis services, run the application, and optionally load the synthetic food fixture. Use only the local example environment. Never add credentials, production data, database dumps, or provider account details to a commit or issue.

## Before a pull request

```bash
./scripts/verify-flyway-migration-immutability.sh
./gradlew --no-daemon test
./gradlew --no-daemon verifyOpenApi
./gradlew --no-daemon bootJar
```

Integration tests use Testcontainers and require Docker. If you change a public route or DTO, regenerate `openapi/openapi.json`, review its diff, and follow [the API versioning policy](docs/api-versioning.md). Do not edit an existing Flyway migration after it has been merged. Add a new migration instead.

Keep changes focused. Explain behavior changes and include tests for fixes or new behavior. Submit only work that you have the right to contribute under the repository's `AGPL-3.0-only` license. Gyro does not require a DCO sign-off or a contributor license agreement.

For security issues, do not open a public issue containing exploit details. Follow [the security policy](SECURITY.md).
