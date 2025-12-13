#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

# ---- User-configurable env ----
ORCH_URL=${ORCH_URL:-http://localhost:8085}
EMPLOYER_ID=${EMPLOYER_ID:-EMP-BENCH}
PAY_PERIOD_ID=${PAY_PERIOD_ID:-2025-01-BW1}
CHECK_DATE=${CHECK_DATE:-2025-01-15}
START_DATE=${START_DATE:-2025-01-01}
END_DATE=${END_DATE:-2025-01-14}

EMPLOYEE_COUNT=${EMPLOYEE_COUNT:-1000}
EMPLOYEE_ID_PREFIX=${EMPLOYEE_ID_PREFIX:-EE-BENCH-}
EMPLOYEE_ID_START=${EMPLOYEE_ID_START:-1}
EMPLOYEE_ID_PAD=${EMPLOYEE_ID_PAD:-6}

# Seed knobs (match hr_seed.sql defaults)
MI_EVERY=${MI_EVERY:-5}
GARNISHMENT_EVERY=${GARNISHMENT_EVERY:-3}

# Compose files for parallel stack
COMPOSE_FILES=(
  -f "$ROOT_DIR/docker-compose.yml"
  -f "$ROOT_DIR/docker-compose.override.yml"
  -f "$ROOT_DIR/docker-compose.bench-parallel.yml"
)

# Worker replica counts to test, comma-separated
WORKER_REPLICAS_CSV=${WORKER_REPLICAS_CSV:-"1,2,4,8"}

# Number of trials (payruns) per worker replica count.
# This is implemented by running the k6 script with RUNS=TRIALS (single VU).
TRIALS=${TRIALS:-3}

# Where to write artifacts
OUT_DIR=${OUT_DIR:-"/tmp/us-payroll-parallel-bench"}
mkdir -p "$OUT_DIR"

SUMMARY_CSV="$OUT_DIR/summary.csv"
SUMMARY_MD="$OUT_DIR/summary.md"

# Internal token shared between worker/orchestrator for internal endpoints
ORCHESTRATOR_INTERNAL_AUTH_SHARED_SECRET=${ORCHESTRATOR_INTERNAL_AUTH_SHARED_SECRET:-dev-internal-token}

# ---- Helpers ----
require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

iso_now() {
  date -u "+%Y-%m-%dT%H:%M:%SZ"
}

# ---- Preconditions ----
require_cmd docker
require_cmd k6
require_cmd awk
require_cmd sed
require_cmd python3

echo "[bench] out_dir=$OUT_DIR"

# ---- Bring up stack (idempotent) ----
# Export the secret so compose picks it up.
export ORCHESTRATOR_INTERNAL_AUTH_SHARED_SECRET

echo "[bench] starting stack (compose)"
docker compose "${COMPOSE_FILES[@]}" up -d --build

# ---- Seed HR/Tax/Labor for requested EMPLOYEE_COUNT ----
echo "[bench] seeding benchmark data (employees=$EMPLOYEE_COUNT)"
EMPLOYER_ID="$EMPLOYER_ID" \
PAY_PERIOD_ID="$PAY_PERIOD_ID" \
START_DATE="$START_DATE" \
END_DATE="$END_DATE" \
CHECK_DATE="$CHECK_DATE" \
EMPLOYEE_COUNT="$EMPLOYEE_COUNT" \
MI_EVERY="$MI_EVERY" \
GARNISHMENT_EVERY="$GARNISHMENT_EVERY" \
"$ROOT_DIR/benchmarks/seed/seed-benchmark-data.sh"

# ---- Run sweep ----
echo "workers,employee_count,trials,elapsed_ms_p50,elapsed_ms_p95,employees_per_sec_p50,employees_per_sec_p95,run_id,artifact_json" > "$SUMMARY_CSV"

read -r -a WORKER_REPLICAS <<<"$(echo "$WORKER_REPLICAS_CSV" | tr ',' ' ')"

for workers in "${WORKER_REPLICAS[@]}"; do
  echo "[bench] scaling payroll-worker-service=$workers"
  docker compose "${COMPOSE_FILES[@]}" up -d --scale payroll-worker-service="$workers"

  run_id="parallel-${EMPLOYEE_COUNT}-w${workers}-$(date +%s)"
  json_out="$OUT_DIR/k6-${run_id}.json"
  txt_out="$OUT_DIR/k6-${run_id}.txt"

  echo "[bench] running k6 run_id=$run_id trials=$TRIALS"
  set +e
  k6 run \
    -e ORCH_URL="$ORCH_URL" \
    -e EMPLOYER_ID="$EMPLOYER_ID" \
    -e PAY_PERIOD_ID="$PAY_PERIOD_ID" \
    -e EMPLOYEE_ID_PREFIX="$EMPLOYEE_ID_PREFIX" \
    -e EMPLOYEE_ID_START="$EMPLOYEE_ID_START" \
    -e EMPLOYEE_ID_PAD="$EMPLOYEE_ID_PAD" \
    -e EMPLOYEE_COUNT="$EMPLOYEE_COUNT" \
    -e VUS=1 \
    -e RUNS="$TRIALS" \
    -e MAX_WAIT_SECONDS=3600 \
    --summary-export "$json_out" \
    "$ROOT_DIR/benchmarks/k6/orchestrator-rabbit-finalize.js" \
    >"$txt_out" 2>&1
  k6_exit=$?
  set -e

  if [[ $k6_exit -ne 0 ]]; then
    echo "[bench] k6 failed for workers=$workers (exit=$k6_exit). See $txt_out" >&2
    continue
  fi

  # Parse end-to-end p50/p95 from the exported JSON.
  elapsed_ms_p50=$(python3 - <<PY
import json
p="$json_out"
with open(p) as f:
  j=json.load(f)
trend=j["metrics"].get("payrun_finalize_rabbit_e2e_ms")
if not trend:
  raise SystemExit("missing metric payrun_finalize_rabbit_e2e_ms")
vals=trend.get("values") or {}
ms=vals.get("med")
if ms is None:
  ms=vals.get("p(50)")
if ms is None:
  ms=vals.get("avg")
print(float(ms))
PY
)

  elapsed_ms_p95=$(python3 - <<PY
import json
p="$json_out"
with open(p) as f:
  j=json.load(f)
trend=j["metrics"].get("payrun_finalize_rabbit_e2e_ms")
if not trend:
  raise SystemExit("missing metric payrun_finalize_rabbit_e2e_ms")
vals=trend.get("values") or {}
ms=vals.get("p(95)")
if ms is None:
  ms=vals.get("max")
if ms is None:
  ms=vals.get("avg")
print(float(ms))
PY
)

  employees_per_sec_p50=$(python3 - <<PY
import json
p="$json_out"
with open(p) as f:
  j=json.load(f)
trend=j["metrics"].get("payrun_finalize_rabbit_employees_per_sec")
if not trend:
  raise SystemExit("missing metric payrun_finalize_rabbit_employees_per_sec")
vals=trend.get("values") or {}
v=vals.get("med")
if v is None:
  v=vals.get("p(50)")
if v is None:
  v=vals.get("avg")
print(float(v))
PY
)

  employees_per_sec_p95=$(python3 - <<PY
import json
p="$json_out"
with open(p) as f:
  j=json.load(f)
trend=j["metrics"].get("payrun_finalize_rabbit_employees_per_sec")
if not trend:
  raise SystemExit("missing metric payrun_finalize_rabbit_employees_per_sec")
vals=trend.get("values") or {}
v=vals.get("p(95)")
if v is None:
  v=vals.get("min")
if v is None:
  v=vals.get("avg")
print(float(v))
PY
)

  echo "[bench] result workers=$workers p50_ms=$elapsed_ms_p50 p95_ms=$elapsed_ms_p95 p50_eps=$employees_per_sec_p50 p95_eps=$employees_per_sec_p95"
  echo "$workers,$EMPLOYEE_COUNT,$TRIALS,$elapsed_ms_p50,$elapsed_ms_p95,$employees_per_sec_p50,$employees_per_sec_p95,$run_id,$json_out" >> "$SUMMARY_CSV"
done

# ---- Write a small markdown report ----
{
  echo "# Parallel payrun benchmark"
  echo "Generated: $(iso_now)"
  echo ""
  echo "Config:"
  echo "- ORCH_URL: $ORCH_URL"
  echo "- EMPLOYER_ID: $EMPLOYER_ID"
  echo "- PAY_PERIOD_ID: $PAY_PERIOD_ID"
  echo "- EMPLOYEE_COUNT: $EMPLOYEE_COUNT"
  echo "- WORKER_REPLICAS: $WORKER_REPLICAS_CSV"
  echo "- TRIALS: $TRIALS"
  echo ""
  echo "Results (employees/sec):"
  echo ""
  echo "workers | employee_count | trials | elapsed_ms p50 | elapsed_ms p95 | employees/sec p50 | employees/sec p95"
  echo "---|---:|---:|---:|---:|---:|---:"
  tail -n +2 "$SUMMARY_CSV" | while IFS=',' read -r w n t ms50 ms95 eps50 eps95 rid art; do
    printf "%s | %s | %s | %.0f | %.0f | %.2f | %.2f\n" "$w" "$n" "$t" "$ms50" "$ms95" "$eps50" "$eps95"
  done
  echo ""
  echo "Artifacts:"
  echo "- $SUMMARY_CSV"
  echo "- $OUT_DIR/k6-*.json (k6 summary exports)"
  echo "- $OUT_DIR/k6-*.txt (k6 logs)"
} > "$SUMMARY_MD"

echo "[bench] wrote: $SUMMARY_CSV"
echo "[bench] wrote: $SUMMARY_MD"
