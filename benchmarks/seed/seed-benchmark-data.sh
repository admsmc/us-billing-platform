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

# Benchmark profile controls how we size and mix the seeded population.
#
# Supported values:
# - PROFILE=stress     (default; original high-coverage mix with dense garnishments)
# - PROFILE=realistic  (more production-like filing statuses, dependents, and garnishment density)
PROFILE=${PROFILE:-stress}

case "${PROFILE}" in
  realistic)
    # Realistic profile: keep EMP-BENCH but use a slightly larger, more typical distribution
    # with lower garnishment density. Callers can still override any of these via env.
    EMPLOYER_ID=${EMPLOYER_ID:-EMP-BENCH}
    PAY_PERIOD_ID=${PAY_PERIOD_ID:-2025-01-BW1}
    START_DATE=${START_DATE:-2025-01-01}
    END_DATE=${END_DATE:-2025-01-14}
    CHECK_DATE=${CHECK_DATE:-2025-01-15}
    EMPLOYEE_COUNT=${EMPLOYEE_COUNT:-400}

    # Mixed population knobs (realistic-ish defaults)
    # - smaller MI/NY/CA hourly cohorts so salaried CA remains a large base
    # - fewer tipped employees
    # - much lower garnishment density by default
    MI_EVERY=${MI_EVERY:-20}
    NY_EVERY=${NY_EVERY:-18}
    CA_HOURLY_EVERY=${CA_HOURLY_EVERY:-16}
    TIPPED_EVERY=${TIPPED_EVERY:-20}
    GARNISHMENT_EVERY=${GARNISHMENT_EVERY:-30}
    ;;
  *)
    # Stress / legacy profile: preserve existing defaults used by docs and k6 scripts.
    EMPLOYER_ID=${EMPLOYER_ID:-EMP-BENCH}
    PAY_PERIOD_ID=${PAY_PERIOD_ID:-2025-01-BW1}
    START_DATE=${START_DATE:-2025-01-01}
    END_DATE=${END_DATE:-2025-01-14}
    CHECK_DATE=${CHECK_DATE:-2025-01-15}
    EMPLOYEE_COUNT=${EMPLOYEE_COUNT:-200}

    # Mixed population knobs (defaults match hr_seed.sql docs)
    MI_EVERY=${MI_EVERY:-5}
    NY_EVERY=${NY_EVERY:-7}
    CA_HOURLY_EVERY=${CA_HOURLY_EVERY:-9}
    TIPPED_EVERY=${TIPPED_EVERY:-11}
    GARNISHMENT_EVERY=${GARNISHMENT_EVERY:-3}
    ;;

esac

# Optional: seed time entries into time-ingestion-service (in-memory) so overtime is time-derived.
# Requires time-ingestion-service to be running and reachable from the host.
SEED_TIME=${SEED_TIME:-false}
TIME_BASE_URL=${TIME_BASE_URL:-http://localhost:8084}
TIME_SEED_CONCURRENCY=${TIME_SEED_CONCURRENCY:-8}
TIME_SEED_TIMEOUT_SECONDS=${TIME_SEED_TIMEOUT_SECONDS:-10}

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

# Flag passed through to hr_seed.sql so it can adjust filing status, dependents,
# and salary distributions when PROFILE=realistic.
if [[ "${PROFILE}" == "realistic" ]]; then
  REALISTIC_PROFILE=1
else
  REALISTIC_PROFILE=0
fi

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
  -v ny_every="${NY_EVERY}" \
  -v ca_hourly_every="${CA_HOURLY_EVERY}" \
  -v tipped_every="${TIPPED_EVERY}" \
  -v garnishment_every="${GARNISHMENT_EVERY}" \
  -v realistic_profile="${REALISTIC_PROFILE}" \
  >/dev/null

seed_time_for_hourly_employees() {
  if [[ "${SEED_TIME}" != "true" ]]; then
    return
  fi

  if ! command -v curl >/dev/null 2>&1; then
    echo "[seed] ERROR: SEED_TIME=true but curl is not installed." >&2
    exit 1
  fi

  if ! command -v python3 >/dev/null 2>&1; then
    echo "[seed] ERROR: SEED_TIME=true but python3 is not installed (needed for date/json generation)." >&2
    exit 1
  fi

  echo "[seed] Seeding time entries via time-ingestion-service at ${TIME_BASE_URL}"

  # Validate we have enough days in the pay period to exercise CA 7th-day rules.
  python3 - <<PY
import datetime
start = datetime.date.fromisoformat("${START_DATE}")
end = datetime.date.fromisoformat("${END_DATE}")
if (end - start).days < 6:
    raise SystemExit("[seed] ERROR: expected at least 7 days between START_DATE and END_DATE to seed CA 7th-day overtime")
PY

  # Returns the state assignment used by hr_seed.sql for a given numeric employee index.
  employee_state_for_n() {
    local n="$1"
    if (( n % NY_EVERY == 0 )); then
      echo "NY"
    elif (( n % MI_EVERY == 0 )); then
      echo "MI"
    elif (( n % CA_HOURLY_EVERY == 0 )); then
      echo "CA"
    else
      echo ""
    fi
  }

  is_hourly_for_n() {
    local n="$1"
    if (( n % NY_EVERY == 0 )); then
      return 0
    elif (( n % MI_EVERY == 0 )); then
      return 0
    elif (( n % CA_HOURLY_EVERY == 0 )); then
      return 0
    else
      return 1
    fi
  }

  # Post a time-entries:bulk payload for one employee.
  seed_employee_time() {
    local employee_id="$1"
    local state="$2"
    local tipped_flag="$3"

    # Hours patterns are designed to deterministically trigger overtime shaping.
    # - CA: daily OT + DT + 7th consecutive day rule.
    # - NY/MI: weekly overtime after 40.

    local payload
    payload=$(python3 - <<PY
import datetime
import json
emp = "${employee_id}"
pay_period_id = "${PAY_PERIOD_ID}"
start = datetime.date.fromisoformat("${START_DATE}")
end = datetime.date.fromisoformat("${END_DATE}")
state = "${state}".upper().strip()
tipped = str("${tipped_flag}").strip().lower() in ("true", "1", "yes")

if state == "CA":
    # 7 consecutive days worked to hit 7th-day rule; include a 13h day to hit DT.
    offsets = [0, 1, 2, 3, 4, 5, 6]
    hours = [9.0, 9.0, 13.0, 9.0, 9.0, 9.0, 8.0]
elif state == "NY":
    # 50h in a workweek.
    offsets = [0, 1, 2, 3, 4]
    hours = [10.0, 10.0, 10.0, 10.0, 10.0]
elif state == "MI":
    # 42h in a workweek.
    offsets = [0, 1, 2, 3, 4]
    hours = [8.5, 8.5, 8.5, 8.5, 8.0]
else:
    offsets = []
    hours = []

# Tip modeling (benchmark synthetic, but worksite-aware):
# - cash tips: varies by day
# - charged tips: varies by day
# - allocated tips: NOT seeded; computed by time-ingestion-service using a tip allocation rule
cash_tips = [0] * len(offsets)
charged_tips = [0] * len(offsets)
worksites = ["MAIN"] * len(offsets)

if tipped and offsets:
    if state == "CA":
        # Higher volume + weekend-ish spikes. Alternate worksites to exercise per-worksite pooling.
        cash_tips =    [4500, 5200, 6100, 4800, 5300, 7200, 6500]
        charged_tips = [9000, 9800, 12500, 9100, 9900, 14000, 13000]
        worksites =    ["BAR", "DINING", "BAR", "DINING", "BAR", "DINING", "BAR"]
    elif state == "NY":
        # More charged than cash.
        cash_tips =    [2000, 2200, 2400, 2100, 2600]
        charged_tips = [11000, 12000, 11500, 13000, 12500]
        worksites =    ["DINING", "DINING", "BAR", "BAR", "DINING"]
    elif state == "MI":
        cash_tips =    [1200, 1400, 1300, 1500, 1600]
        charged_tips = [5500, 6000, 5800, 6200, 6500]
        worksites =    ["DINING", "DINING", "DINING", "BAR", "BAR"]

# Deterministic “real world” additional earnings for a subset of employees.
# Use employee numeric suffix so this stays stable across runs.
try:
    n = int(emp.split("-")[-1])
except Exception:
    n = 0

commission_total = 0
bonus_total = 0
reimb_total = 0

# About ~7-8% commission, ~6% bonus, ~5% reimbursements at default sizes.
if n > 0 and (n % 13) == 0:
    commission_total = 25000 + (n % 5) * 10000   # $250-$650
if n > 0 and (n % 17) == 0:
    bonus_total = 50000 + (n % 4) * 25000       # $500-$1,250
if n > 0 and (n % 19) == 0:
    reimb_total = 1200 + (n % 6) * 800          # $12-$52 (non-taxable reimbursement)

entries = []
for idx, (off, h) in enumerate(zip(offsets, hours)):
    d = (start + datetime.timedelta(days=off))
    if d > end:
        continue
    ds = d.isoformat()

    item = {
        "entryId": f"bench-{pay_period_id}-{emp}-{ds}",
        "date": ds,
        "hours": h,
        "worksiteKey": worksites[idx] if idx < len(worksites) else "MAIN",
    }

    # Only include tip fields when tipped, to keep payload small for non-tipped employees.
    # allocatedTipsCents is computed by time-ingestion-service (tip pool allocation rule).
    if tipped:
        item["cashTipsCents"] = int(cash_tips[idx])
        item["chargedTipsCents"] = int(charged_tips[idx])

    # Put non-hour earnings on a single day to mimic "per-period" supplemental earnings.
    if idx == 0 and commission_total > 0:
        item["commissionCents"] = int(commission_total)
    if idx == 0 and bonus_total > 0:
        item["bonusCents"] = int(bonus_total)
    if idx == 1 and reimb_total > 0:
        item["reimbursementNonTaxableCents"] = int(reimb_total)

    entries.append(item)

print(json.dumps({"entries": entries}))
PY
    )

    curl --fail --silent --show-error \
      --max-time "${TIME_SEED_TIMEOUT_SECONDS}" \
      -H 'Content-Type: application/json' \
      -X POST "${TIME_BASE_URL}/employers/${EMPLOYER_ID}/employees/${employee_id}/time-entries:bulk" \
      -d "${payload}" \
      >/dev/null
  }

  # Seed in parallel (best-effort) to keep local setup snappy.
  # Note: we only seed hourly employees by the same cohort rules used in hr_seed.sql.
  export -f seed_employee_time
  export -f employee_state_for_n
  export EMPLOYER_ID PAY_PERIOD_ID TIME_BASE_URL TIME_SEED_TIMEOUT_SECONDS
  export START_DATE END_DATE
  export NY_EVERY MI_EVERY CA_HOURLY_EVERY

  # Build a simple worklist: one line per employee: "employeeId state".
  worklist=$(python3 - <<PY
import sys
count = int("${EMPLOYEE_COUNT}")
ny = int("${NY_EVERY}")
mi = int("${MI_EVERY}")
ca = int("${CA_HOURLY_EVERY}")
tipped_every = int("${TIPPED_EVERY}")
for n in range(1, count + 1):
    state = None
    if n % ny == 0:
        state = "NY"
    elif n % mi == 0:
        state = "MI"
    elif n % ca == 0:
        state = "CA"

    if not state:
        continue

    tipped = (tipped_every > 0 and (n % tipped_every) == 0)
    tipped_flag = "true" if tipped else "false"
    sys.stdout.write(f"EE-BENCH-{n:06d} {state} {tipped_flag}\n")
PY
  )

  echo "${worklist}" | xargs -P "${TIME_SEED_CONCURRENCY}" -n 3 bash -c 'seed_employee_time "$0" "$1" "$2"'

  echo "[seed] Time entries seeded (SEED_TIME=true)."
}

# Optional 1b) Time seed (in-memory time-ingestion-service)
seed_time_for_hourly_employees

# 2) Tax import
#
# This is a two-step process:
#  - CSV -> JSON: generate state-income-$YEAR.json into tax-content/src/main/resources/tax-config
#  - JSON -> DB: import a curated set of tax-config JSON into the tax_rule table
#
# The runtime tax-service reads from the DB-backed tax catalog (tax_rule), so
# without the JSON->DB step, workers will see an empty TaxContext.

export TAX_DB_URL="jdbc:postgresql://${PGHOST}:${PGPORT}/${TAX_DB}"
export TAX_DB_USERNAME
export TAX_DB_PASSWORD

TAX_YEAR=${TAX_YEAR:-2025}

echo "[seed] Generating state income tax JSON (taxYear=${TAX_YEAR})"
"${ROOT_DIR}/scripts/gradlew-java21.sh" :tax-service:runStateIncomeTaxImporter --no-daemon -PtaxYear="${TAX_YEAR}" >/dev/null

echo "[seed] Importing tax-config JSON into ${TAX_DB}.tax_rule (taxYear=${TAX_YEAR})"
"${ROOT_DIR}/scripts/gradlew-java21.sh" :tax-service:importTaxConfigToDb --no-daemon -PtaxYear="${TAX_YEAR}" -Ptruncate=true >/dev/null

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
