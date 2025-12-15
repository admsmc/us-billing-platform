#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Provision per-tenant Postgres databases/roles for DB-per-employer tenancy.

Env (required):
  TENANTS_CSV         Comma-separated tenant/employer ids (e.g. EMP1,EMP2)

Env (optional):
  SERVICES_CSV        Comma-separated services (default: hr,tax,labor,orchestrator)
  DB_PREFIX           DB name prefix (default: us_payroll)
  ROLE_PREFIX         Role name prefix (default: tenant)
  TENANT_DB_PASSWORD  Password used for created roles (dev-only; default: tenant_password)
  DRY_RUN             If 'true', only print commands (default: false)

Connection:
  Uses standard libpq env vars: PGHOST, PGPORT, PGUSER, PGPASSWORD, PGDATABASE.
  You typically want PGUSER to be a role that can CREATE ROLE/DB.

Examples:
  TENANTS_CSV=EMP1,EMP2 PGHOST=localhost PGPORT=15432 PGUSER=postgres PGPASSWORD=postgres \
    DRY_RUN=true ./scripts/ops/tenancy-provision.sh

  TENANTS_CSV=EMP1,EMP2 PGHOST=localhost PGPORT=15432 PGUSER=postgres PGPASSWORD=postgres \
    ./scripts/ops/tenancy-provision.sh
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

: "${TENANTS_CSV:?TENANTS_CSV is required}"

SERVICES_CSV=${SERVICES_CSV:-hr,tax,labor,orchestrator}
DB_PREFIX=${DB_PREFIX:-us_payroll}
ROLE_PREFIX=${ROLE_PREFIX:-tenant}
TENANT_DB_PASSWORD=${TENANT_DB_PASSWORD:-tenant_password}
DRY_RUN=${DRY_RUN:-false}

IFS=',' read -r -a TENANTS <<<"$TENANTS_CSV"
IFS=',' read -r -a SERVICES <<<"$SERVICES_CSV"

psql_exec() {
  local sql="$1"
  if [[ "$DRY_RUN" == "true" ]]; then
    echo "psql -v ON_ERROR_STOP=1 -c \"$sql\""
  else
    psql -v ON_ERROR_STOP=1 -c "$sql" >/dev/null
  fi
}

for tenant in "${TENANTS[@]}"; do
  tenant=$(echo "$tenant" | xargs)
  [[ -n "$tenant" ]] || continue

  for svc in "${SERVICES[@]}"; do
    svc=$(echo "$svc" | xargs)
    [[ -n "$svc" ]] || continue

    db_name="${DB_PREFIX}_${svc}__${tenant}"
    role_name="${ROLE_PREFIX}_${svc}__${tenant}"

    # Role is a dev helper; in production, prefer secrets manager + least-privilege per service.
    psql_exec "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${role_name}') THEN CREATE ROLE ${role_name} LOGIN PASSWORD '${TENANT_DB_PASSWORD}'; END IF; END $$;"

    # Create DB if missing and set owner.
    psql_exec "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = '${db_name}') THEN CREATE DATABASE ${db_name} OWNER ${role_name}; END IF; END $$;"

    # Ensure ownership (idempotent).
    psql_exec "ALTER DATABASE ${db_name} OWNER TO ${role_name};"
  done

done

echo "Done. Provisioned tenants: ${TENANTS_CSV}; services: ${SERVICES_CSV}"