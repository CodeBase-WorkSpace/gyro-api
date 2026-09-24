# API contract and versioning

`openapi/openapi.json` is the reviewed, machine-readable contract for `/api/v1`. It is generated from the running
Spring application by `./gradlew generateOpenApi`. The integration test generates the document twice in one process,
sorts JSON object keys, and compares the result byte for byte with the committed artifact during `./gradlew test` or
`./gradlew verifyOpenApi`. Run generation and verification with Docker available because the test uses PostgreSQL and
Redis Testcontainers. Generate twice in separate processes before a release to confirm process-independent output.

The generation test enables optional Telegram routes with inert example configuration so the artifact covers supported
endpoints even when local development disables that provider. It does not send a Telegram request. The OpenAPI `servers`
entry is relative and contains no deployment hostname.

`info.version` uses semantic versioning for the HTTP contract. The `/api/v1` URL prefix is the major-version boundary.

- Additive operations and optional fields normally increment the minor version when released.
- Documentation-only corrections increment the patch version.
- Removing an operation, changing its method or path, making a request stricter, or changing a response incompatibly
  requires a planned `/api/v2` contract. Keep `/api/v1` available until all pinned clients have migrated.
- Do not raise only `info.version` to excuse a breaking `/api/v1` change. Review the OASDiff findings and implement a
  parallel version or a compatible migration.

The public API CI template under `.github/workflows/contract-ci.yml.disabled` first checks the committed artifact,
generates it in two separate processes, and requires both results to match the committed bytes. It then validates the
spec and compares it with the pull request base using OASDiff. Definite `ERR`-level breaking changes fail the check;
review `WARN` findings manually. OASDiff's hosted review upload is disabled, so the specs stay on the CI runner.
The template is disabled in this private repository under ADR 0006. Enable it in `gyro-api` only after the Actions
quota decision has been revisited. Local review can run `verifyOpenApi` and
`oasdiff breaking --fail-on ERR <base-openapi.json> openapi/openapi.json` using the same contract files.

For each API release, publish the exact `openapi/openapi.json` beside the immutable image digest. Consumers pin the
artifact SHA-256 and record the compatible API release. `gyro-web` currently stores a Phase 1 candidate copy in
`openapi/openapi.json` with its digest in `openapi/source.json`; replace it with a released artifact at cutover.
`pnpm run contract:check` verifies that copy and checks its direct HTTP calls.
This temporary check covers routes and methods. It does not prove DTO field compatibility, so response and request
shape changes also require normal frontend tests and review until a generated client replaces handwritten DTOs.
