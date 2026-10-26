#!/usr/bin/env bash
# Docker Compose check: builds the jar and image, starts PostgreSQL 17 + app, and prints health and
# the Flyway history. Requires a .env (see .env.example) with a real JWT_SIGNING_KEY.
set -euo pipefail
cd "$(dirname "$0")/.."

[[ -f .env ]] || { echo "Create .env from .env.example first" >&2; exit 1; }
set -a; source .env; set +a
port="${SERVER_PORT:-8080}"

./mvnw -B -ntp -q package -DskipTests
docker compose up -d --build

for _ in $(seq 1 60); do
  curl -sf "localhost:${port}/actuator/health/readiness" >/dev/null && break
  sleep 2
done

echo "liveness:  $(curl -s "localhost:${port}/actuator/health/liveness")"
echo "readiness: $(curl -s "localhost:${port}/actuator/health/readiness")"
echo "postgres:  $(docker compose exec -T postgres psql -U "$DB_USERNAME" -d "${DB_NAME:-aiws}" -tAc 'SHOW server_version')"
echo "flyway:"
docker compose exec -T postgres psql -U "$DB_USERNAME" -d "${DB_NAME:-aiws}" -tAc \
  "SELECT version, script, success FROM flyway_schema_history ORDER BY installed_rank"
echo "Stop with: docker compose down -v"
