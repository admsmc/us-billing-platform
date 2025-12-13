#!/usr/bin/env bash
set -euo pipefail

# Seeds data into local Postgres for the orchestrator finalize+execute k6 macrobench.
#
# Enterprise-ish workflow:
# - Tax/Labor: use the *real* importers so DB content matches production pipelines.
# - HR: seed via SQL (hr-service owns HR schema, but we keep this seed isolated to a benchmark employer).
#
# Prereqs:
# - Postgres running (e.g. via `docker compose up -d postgres`).
# - Schemas migrated. Easiest path: start services once (hr/tax/labor/orchestrator) so Flyway runs.
#
# Usage example:
#   EMPLOYER_ID=EMP-BENCH \
#   PAY_PERIOD_ID=2025-01-BW1 \
#   CHECK_DATE=2025-01-15 \
#   EMPLOYEE_COUNT=200 \
#   ./benchmarks/seed/seed-benchmark-data.sh

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)

EMPLOYER_ID=${EMPLOYER_ID:-EMP-BENCH}
PAY_PERIOD_ID=${PAY_PERIOD_ID:-2025-01-BW1}
START_DATE=${START_DATE:-2025-01-01}
END_DATE=${END_DATE:-2025-01-14}
CHECK_DATE=${CHECK_DATE:-2025-01-15}
EMPLOYEE_COUNT=${EMPLOYEE_COUNT:-200}

# Mixed population knobs (defaults match hr_seed.sql docs)
MI_EVERY=${MI_EVERY:-5}
GARNISHMENT_EVERY=${GARNISHMENT_EVERY:-3}

# Postgres connection (override as needed)
# NOTE: docker-compose publishes Postgres on host port 15432 (see docker-compose.yml).
PGHOST=${PGHOST:-localhost}
PGPORT=${PGPORT:-15432}
PGUSER=${PGUSER:-hr_service}
PGPASSWORD=${PGPASSWORD:-hr_service}
export PGPASSWORD

HR_DB=${HR_DB:-us_payroll_hr}
TAX_DB=${TAX_DB:-us_payroll_tax}
LABOR_DB=${LABOR_DB:-us_payroll_labor}

TAX_DB_USERNAME=${TAX_DB_USERNAME:-tax_service}
TAX_DB_PASSWORD=${TAX_DB_PASSWORD:-tax_service}
LABOR_DB_USERNAME=${LABOR_DB_USERNAME:-labor_service}
LABOR_DB_PASSWORD=${LABOR_DB_PASSWORD:-labor_service}

# 1) HR seed
# Note: we connect as hr_service to the HR DB.
# Prefer host `psql`, but fall back to running `psql` inside the dockerized Postgres container
# (since not all dev machines have psql installed).
echo "[seed] Seeding HR data into ${HR_DB} for employer ${EMPLOYER_ID} (${EMPLOYEE_COUNT} employees)"

resolve_pg_container() {
  local pg_container
  pg_container=$(docker ps -q --filter "label=com.docker.compose.service=postgres" | head -n 1 || true)
  if [[ -z "${pg_container}" ]]; then
    echo "[seed] ERROR: psql not found and no docker compose postgres container detected (label com.docker.compose.service=postgres)." >&2
    echo "[seed]        Install psql or start the compose stack (postgres service) before seeding." >&2
    exit 1
  fi
  echo "${pg_container}"
}

run_psql_file() {
  local conn_host="$1"
  local conn_docker="$2"
  local sql_file="$3"
  shift 3

  if command -v psql >/dev/null 2>&1; then
    psql "${conn_host}" "$@" -f "${sql_file}"
    return
  fi

  local pg_container
  pg_container=$(resolve_pg_container)

  # NOTE: the SQL file path is on the host; stream it via stdin.
  docker exec -i \
    -e PGPASSWORD="${PGPASSWORD}" \
    "${pg_container}" \
    psql "${conn_docker}" "$@" \
    <"${sql_file}"
}

run_psql_file \
  "host=${PGHOST} port=${PGPORT} dbname=${HR_DB} user=${PGUSER}" \
  "host=localhost port=5432 dbname=${HR_DB} user=${PGUSER}" \
  "${ROOT_DIR}/benchmarks/seed/hr_seed.sql" \
  -v employer_id="${EMPLOYER_ID}" \
  -v pay_period_id="${PAY_PERIOD_ID}" \
  -v start_date="${START_DATE}" \
  -v end_date="${END_DATE}" \
  -v check_date="${CHECK_DATE}" \
  -v employee_count="${EMPLOYEE_COUNT}" \
  -v mi_every="${MI_EVERY}" \
  -v garnishment_every="${GARNISHMENT_EVERY}" \
  >/dev/null

# 2) Tax import (real pipeline)
# This runs the importer directly against Postgres so tax-service can answer requests.
# Uses the same tasks as the repo's tax content pipeline.

export TAX_DB_URL="jdbc:postgresql://${PGHOST}:${PGPORT}/${TAX_DB}"
export TAX_DB_USERNAME
export TAX_DB_PASSWORD

TAX_YEAR=${TAX_YEAR:-2025}

echo "[seed] Importing tax rules into ${TAX_DB} (taxYear=${TAX_YEAR})"
"${ROOT_DIR}/scripts/gradlew-java21.sh" :tax-service:runStateIncomeTaxImporter --no-daemon -PtaxYear="${TAX_YEAR}" >/dev/null

# 3) Labor import (real pipeline)
export LABOR_DB_URL="jdbc:postgresql://${PGHOST}:${PGPORT}/${LABOR_DB}"
export LABOR_DB_USERNAME
export LABOR_DB_PASSWORD

LABOR_YEAR=${LABOR_YEAR:-2025}

echo "[seed] Importing labor standards into ${LABOR_DB} (laborYear=${LABOR_YEAR})"
"${ROOT_DIR}/scripts/gradlew-java21.sh" :labor-service:runLaborStandardsImporter --no-daemon -PlaborYear="${LABOR_YEAR}" >/dev/null

echo "[seed] Done. Employer=${EMPLOYER_ID} payPeriodId=${PAY_PERIOD_ID} checkDate=${CHECK_DATE} employeeCount=${EMPLOYEE_COUNT}"

echo "[seed] Example EMPLOYEE_IDS for k6 (these should have garnishments if GARNISHMENT_EVERY=${GARNISHMENT_EVERY}):"
first_with_garn=$(printf "EE-BENCH-%06d" "${GARNISHMENT_EVERY}")
second_with_garn=$(printf "EE-BENCH-%06d" "$((GARNISHMENT_EVERY * 2))")

if [[ "${EMPLOYEE_COUNT}" -ge "$((GARNISHMENT_EVERY * 2))" ]]; then
  echo "${first_with_garn},${second_with_garn}"
elif [[ "${EMPLOYEE_COUNT}" -ge "${GARNISHMENT_EVERY}" ]]; then
  echo "${first_with_garn}"
else
  echo "EE-BENCH-000001"
fi
