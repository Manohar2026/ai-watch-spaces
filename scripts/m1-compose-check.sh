#!/usr/bin/env bash
# M1-V2 / M1-V13 check under Docker Compose: builds the jar and image, starts PostgreSQL 17 + app,
# and verifies health and Flyway. Pass --with-probe to also apply the test-only M1 probe migration
# (the application has no migrations of its own until M2). Requires a .env (see .env.example).
set -euo pipefail
cd "$(dirname "$0")/.."

[[ -f .env ]] || { echo "Create .env from .env.example first" >&2; exit 1; }
set -a; source .env; set +a
port="${SERVER_PORT:-8080}"

files=(-f compose.yaml)
if [[ "${1:-}" == "--with-probe" ]]; then
  override="$(mktemp --suffix=.yaml)"
  trap 'rm -f "$override"' EXIT
  cat > "$override" <<YAML
services:
  app:
    environment:
      SPRING_FLYWAY_LOCATIONS: classpath:db/migration,filesystem:/m1-probe
    volumes:
      - $(pwd)/src/test/resources/db/m1-probe:/m1-probe:ro
YAML
  files+=(-f "$override")
fi

./mvnw -B -ntp -q package -DskipTests
docker compose "${files[@]}" up -d --build

for _ in $(seq 1 60); do
  curl -sf "localhost:${port}/actuator/health/readiness" >/dev/null && break
  sleep 2
done

echo "liveness:  $(curl -s "localhost:${port}/actuator/health/liveness")"
echo "readiness: $(curl -s "localhost:${port}/actuator/health/readiness")"
echo "postgres:  $(docker compose exec -T postgres psql -U "$DB_USERNAME" -d "${DB_NAME:-aiws}" -tAc 'SHOW server_version')"
echo "flyway:"
docker compose exec -T postgres psql -U "$DB_USERNAME" -d "${DB_NAME:-aiws}" -tAc \
  "SELECT version, script, success FROM flyway_schema_history ORDER BY installed_rank" || true
echo "Stop with: docker compose ${files[*]} down -v"
