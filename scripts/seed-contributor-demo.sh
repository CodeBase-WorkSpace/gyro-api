#!/usr/bin/env bash
set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly API_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

cd "$API_ROOT"

if [[ ! -f .env ]]; then
    echo "Create .env from .env.example before seeding contributor data." >&2
    exit 1
fi

# This fixture deliberately supports only the public example database. A real
# database export or a shared environment must never become a shortcut here.
if ! grep -Fxq 'SPRING_PROFILES_ACTIVE=dev' .env ||
   ! grep -Fxq 'POSTGRES_DB=contributor_db' .env ||
   ! grep -Fxq 'POSTGRES_USER=contributor' .env; then
    echo "Contributor seed requires the dev profile and example Compose database/user." >&2
    exit 1
fi

if [[ -n "${DOCKER_HOST:-}" ]]; then
    echo "Contributor seed refuses a custom DOCKER_HOST; use a local Docker context." >&2
    exit 1
fi

docker_endpoint="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
if [[ "$docker_endpoint" != unix://* ]]; then
    echo "Contributor seed requires a local Unix-socket Docker context." >&2
    exit 1
fi

docker compose --project-name gyro-api-local --env-file .env exec -T postgres \
    psql -X -v ON_ERROR_STOP=1 -U contributor -d contributor_db \
    < "$SCRIPT_DIR/seed-contributor-demo.sql"

echo "Synthetic contributor foods are ready in the local database."
