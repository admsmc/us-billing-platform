#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Run Flyway migrations per tenant DB (DB-per-employer) using the Flyway CLI Docker image.

Env (required):
  SERVICE             One of: hr, labor, orchestrator, tax
  TENANTS_CSV         Comma-separated tenant/employer ids

Env (optional):
  DB_PREFIX           DB name prefix (default: us_payroll)
  DB_HOST             Postgres host (default: localhost)
  DB_PORT             Postgres port (default: 5432)
  DB_USER             DB username (default: postgres)
  DB_PASSWORD         DB password (default: postgres)
  DRY_RUN             If 'true', only print commands (default: false)

Notes:
- This script assumes the per-tenant DB name convention created by `scripts/ops/tenancy-provision.sh`:
  ${DB_PREFIX}_${SERVICE}__${TENANT}
- In production, prefer platform-native migration orchestration, but this is a strong repo-local baseline.

Example:
  SERVICE=orchestrator TENANTS_CSV=EMP1,EMP2 DB_HOST=localhost DB_PORT=15432 DB_USER=postgres DB_PASSWORD=postgres \
    ./scripts/ops/tenancy-migrate.sh
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

: "${SERVICE:?SERVICE is required}"
: "${TENANTS_CSV:?TENANTS_CSV is required}"

DB_PREFIX=${DB_PREFIX:-us_payroll}
DB_HOST=${DB_HOST:-localhost}
DB_PORT=${DB_PORT:-5432}
DB_USER=${DB_USER:-postgres}
DB_PASSWORD=${DB_PASSWORD:-postgres}
DRY_RUN=${DRY_RUN:-false}

case "$SERVICE" in
  hr)
    MIGRATIONS_DIR="hr-service/src/main/resources/db/migration/hr"
    ;;
  labor)
    MIGRATIONS_DIR="labor-service/src/main/resources/db/migration/labor"
    ;;
  orchestrator)
    MIGRATIONS_DIR="payroll-orchestrator-service/src/main/resources/db/migration/orchestrator"
    ;;
  tax)
    MIGRATIONS_DIR="tax-content/src/main/resources/db/migration/tax"
    ;;
  *)
    echo "Unknown SERVICE: $SERVICE" >&2
    exit 1
    ;;
esac

IFS=',' read -r -a TENANTS <<<"$TENANTS_CSV"

for tenant in "${TENANTS[@]}"; do
  tenant=$(echo "$tenant" | xargs)
  [[ -n "$tenant" ]] || continue

  db_name="${DB_PREFIX}_${SERVICE}__${tenant}"
  url="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${db_name}"

  cmd=(
    docker run --rm
    -v "${PWD}/${MIGRATIONS_DIR}:/flyway/sql:ro"
    flyway/flyway:10.19.0
    -url="$url"
    -user="$DB_USER"
    -password="$DB_PASSWORD"
    migrate
  )

  if [[ "$DRY_RUN" == "true" ]]; then
    printf '%q ' "${cmd[@]}"; echo
  else
    "${cmd[@]}"
  fi

done

echo "Done. Migrated SERVICE=${SERVICE} tenants=${TENANTS_CSV}"