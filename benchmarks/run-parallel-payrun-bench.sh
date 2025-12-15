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
  -f "$ROOT_DIR/docker-compose.bench-ports.yml"
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

# Docker Desktop builds can OOM when Compose/BuildKit builds multiple Gradle images concurrently.
# Default to a sequential build to reduce peak memory usage.
BENCH_DOCKER_BUILD_STRATEGY=${BENCH_DOCKER_BUILD_STRATEGY:-sequential}

if [[ "$BENCH_DOCKER_BUILD_STRATEGY" == "sequential" ]]; then
  # Compose may use Buildx Bake (parallel builds) depending on local defaults.
  # Disabling Bake keeps peak memory lower and makes the build path more predictable.
  export COMPOSE_BAKE=${COMPOSE_BAKE:-false}

  for svc in \
    hr-service \
    tax-service \
    labor-service \
    payroll-orchestrator-service \
    payroll-worker-service \
    edge-service
  do
    echo "[bench] building image (sequential) service=$svc"
    docker compose "${COMPOSE_FILES[@]}" build "$svc"
  done

  docker compose "${COMPOSE_FILES[@]}" up -d
else
  docker compose "${COMPOSE_FILES[@]}" up -d --build
fi

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
echo "workers,employee_count,trials,elapsed_ms_p50,elapsed_ms_p95,employees_per_sec_p50,employees_per_sec_p95,status_polls_p50,status_polls_p95,poll_sleep_ms_p50,poll_sleep_ms_p95,poll_quantization_ratio_p50,poll_quantization_ratio_p95,server_elapsed_ms_p50,server_elapsed_ms_p95,server_employees_per_sec_p50,server_employees_per_sec_p95,db_cpu_pct_max,db_connections_max,worker_finalize_avg_ms,orchestrator_finalize_avg_ms,run_id,artifact_json,artifact_metrics_csv" > "$SUMMARY_CSV"

read -r -a WORKER_REPLICAS <<<"$(echo "$WORKER_REPLICAS_CSV" | tr ',' ' ')"

for workers in "${WORKER_REPLICAS[@]}"; do
  echo "[bench] scaling payroll-worker-service=$workers"
  docker compose "${COMPOSE_FILES[@]}" up -d --scale payroll-worker-service="$workers"

  run_id="parallel-${EMPLOYEE_COUNT}-w${workers}-$(date +%s)"
  json_out="$OUT_DIR/k6-${run_id}.json"
  txt_out="$OUT_DIR/k6-${run_id}.txt"
  metrics_csv="$OUT_DIR/metrics-${run_id}.csv"

  # --- Metrics capture (DB + worker/orchestrator timers) ---
  # We sample Postgres container CPU% and active connection count during the run.
  # Additionally, we snapshot Micrometer timer counters before/after so we can compute avg durations.

  echo "ts_epoch,postgres_cpu_pct,postgres_connections" > "$metrics_csv"

  # Identify current worker replicas' published host ports for container port 8088.
  # (docker-compose.bench-ports.yml publishes 8088 on a random host port per replica)
  get_worker_host_ports() {
    docker ps \
      --filter "label=com.docker.compose.project=us-payroll-platform" \
      --filter "label=com.docker.compose.service=payroll-worker-service" \
      --format '{{.Ports}}' \
      | sed -nE 's/.*0\.0\.0\.0:([0-9]+)->8088\/tcp.*/\1/p; s/.*\[::\]:([0-9]+)->8088\/tcp.*/\1/p'
  }

  scrape_timer_totals() {
    # Args: base_url meter_name
    # Prints: "<totalTimeSeconds> <count>"
    local base_url="$1"
    local meter="$2"

    local body=""
    for _ in 1 2 3 4 5; do
      body=$(curl -sS --max-time 10 "$base_url/actuator/metrics/$meter" || true)
      if [[ -n "$body" ]]; then
        break
      fi
      sleep 1
    done

    if [[ -z "$body" ]]; then
      printf "0 0\n"
      return
    fi

    printf '%s' "$body" | python3 -c '
import json, sys

try:
  j = json.load(sys.stdin)
except Exception:
  print("0 0")
  raise SystemExit(0)

meas = j.get("measurements") or []
count = None
sum_s = None
for m in meas:
  stat = m.get("statistic")
  val = m.get("value")
  if stat == "COUNT":
    count = val
  elif stat == "TOTAL_TIME":
    sum_s = val

if sum_s is None:
  sum_s = 0
if count is None:
  count = 0

print(f"{sum_s} {int(count)}")
'
  }

  sum_worker_timer_totals() {
    # Prints: "<sum> <count>" across all replicas
    local total_sum="0"
    local total_cnt="0"

    local ports
    ports=$(get_worker_host_ports | tr '\n' ' ')

    for p in $ports; do
      # worker service exposes actuator on same port as SERVER_PORT (8088)
      read -r s c < <(scrape_timer_totals "http://localhost:$p" "worker.payrun.finalize_employee.duration")
      total_sum=$(python3 - <<PY
import decimal
print(decimal.Decimal("$total_sum") + decimal.Decimal("$s"))
PY
)
      total_cnt=$(python3 - <<PY
import decimal
print(decimal.Decimal("$total_cnt") + decimal.Decimal("$c"))
PY
)
    done

    printf "%s %s\n" "$total_sum" "$total_cnt"
  }

  # Pre-run snapshots
  read -r worker_sum_before worker_cnt_before < <(sum_worker_timer_totals)
  read -r orch_sum_before orch_cnt_before < <(scrape_timer_totals "http://localhost:8085" "orchestrator.payrun.item.finalize.duration")

  # Start background DB sampler
  (
    while true; do
      ts=$(date +%s)
      cpu=$(docker stats --no-stream --format '{{.CPUPerc}}' us-payroll-platform-postgres-1 2>/dev/null | tr -d '%')
      conns=$(docker exec us-payroll-platform-postgres-1 psql -U postgres -d postgres -t -A -c "SELECT count(*) FROM pg_stat_activity;" 2>/dev/null | tr -d '[:space:]')
      cpu=${cpu:-}
      conns=${conns:-}
      echo "$ts,$cpu,$conns" >> "$metrics_csv"
      sleep 5
    done
  ) &
  metrics_pid=$!

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
    -e MAX_WAIT_SECONDS="${MAX_WAIT_SECONDS:-3600}" \
    -e POLL_STRATEGY="${POLL_STRATEGY:-fixed}" \
    --summary-export "$json_out" \
    "$ROOT_DIR/benchmarks/k6/orchestrator-rabbit-finalize.js" \
    >"$txt_out" 2>&1
  k6_exit=$?
  set -e

  # Stop metrics sampler
  kill "$metrics_pid" >/dev/null 2>&1 || true
  wait "$metrics_pid" >/dev/null 2>&1 || true

  if [[ $k6_exit -ne 0 ]]; then
    echo "[bench] k6 failed for workers=$workers (exit=$k6_exit). See $txt_out" >&2
    continue
  fi

  # Post-run snapshots for timers
  read -r worker_sum_after worker_cnt_after < <(sum_worker_timer_totals)
  read -r orch_sum_after orch_cnt_after < <(scrape_timer_totals "http://localhost:8085" "orchestrator.payrun.item.finalize.duration")

  # Compute avg duration per finalize across all workers/orchestrator (ms) using deltas
  worker_finalize_avg_ms=$(python3 - <<PY
import decimal
sb=decimal.Decimal("$worker_sum_before")
sa=decimal.Decimal("$worker_sum_after")
cb=decimal.Decimal("$worker_cnt_before")
ca=decimal.Decimal("$worker_cnt_after")
dc=ca-cb
ds=sa-sb
if dc <= 0:
  print('nan')
else:
  print(float((ds/dc)*decimal.Decimal(1000)))
PY
)

  orchestrator_finalize_avg_ms=$(python3 - <<PY
import decimal
sb=decimal.Decimal("$orch_sum_before")
sa=decimal.Decimal("$orch_sum_after")
cb=decimal.Decimal("$orch_cnt_before")
ca=decimal.Decimal("$orch_cnt_after")
dc=ca-cb
ds=sa-sb
if dc <= 0:
  print('nan')
else:
  print(float((ds/dc)*decimal.Decimal(1000)))
PY
)

  # DB maxes during run
  db_cpu_pct_max=$(awk -F',' 'NR>1 && $2 != "" { if ($2+0 > m) m=$2+0 } END { if (m=="") print "nan"; else printf("%.2f", m) }' "$metrics_csv")
  db_connections_max=$(awk -F',' 'NR>1 && $3 != "" { if ($3+0 > m) m=$3+0 } END { if (m=="") print "nan"; else printf("%d", m) }' "$metrics_csv")

  # Parse end-to-end p50/p95 from the exported JSON.
  elapsed_ms_p50=$(python3 - <<PY
import json
p="$json_out"
with open(p) as f:
  j=json.load(f)
trend=j["metrics"].get("payrun_finalize_rabbit_e2e_ms")
if not trend:
  raise SystemExit("missing metric payrun_finalize_rabbit_e2e_ms")
# k6 summary-export JSON can be either:
# - {"values": {...}} (older)
# - {...} (newer; values at the top-level)
vals = trend.get("values") if isinstance(trend, dict) else None
if not vals:
  vals = trend
ms = vals.get("med")
if ms is None:
  ms = vals.get("p(50)")
if ms is None:
  ms = vals.get("avg")
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
vals = trend.get("values") if isinstance(trend, dict) else None
if not vals:
  vals = trend
ms = vals.get("p(95)")
if ms is None:
  ms = vals.get("max")
if ms is None:
  ms = vals.get("avg")
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
vals = trend.get("values") if isinstance(trend, dict) else None
if not vals:
  vals = trend
v = vals.get("med")
if v is None:
  v = vals.get("p(50)")
if v is None:
  v = vals.get("avg")
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
vals = trend.get("values") if isinstance(trend, dict) else None
if not vals:
  vals = trend
v = vals.get("p(95)")
if v is None:
  v = vals.get("min")
if v is None:
  v = vals.get("avg")
print(float(v))
PY
)

  status_polls_p50=$(python3 - <<PY
import json, math
p="$json_out"
with open(p) as f:
  j=json.load(f)
trend=j["metrics"].get("payrun_finalize_rabbit_status_polls")
if not trend:
  print(float('nan'))
  raise SystemExit(0)
vals = trend.get("values") if isinstance(trend, dict) else None
if not vals:
  vals = trend
v = vals.get("med")
if v is None:
  v = vals.get("p(50)")
if v is None:
  v = vals.get("avg")
print(float('nan') if v is None else float(v))
PY
)

  status_polls_p95=$(python3 - <<PY
import json, math
p="$json_out"
with open(p) as f:
  j=json.load(f)
trend=j["metrics"].get("payrun_finalize_rabbit_status_polls")
if not trend:
  print(float('nan'))
  raise SystemExit(0)
vals = trend.get("values") if isinstance(trend, dict) else None
if not vals:
  vals = trend
v = vals.get("p(95)")
if v is None:
  v = vals.get("max")
if v is None:
  v = vals.get("avg")
print(float('nan') if v is None else float(v))
PY
)

  poll_sleep_ms_p50=$(python3 - <<PY
import json, math
p="$json_out"
with open(p) as f:
  j=json.load(f)
trend=j["metrics"].get("payrun_finalize_rabbit_poll_sleep_ms")
if not trend:
  print(float('nan'))
  raise SystemExit(0)
vals = trend.get("values") if isinstance(trend, dict) else None
if not vals:
  vals = trend
v = vals.get("med")
if v is None:
  v = vals.get("p(50)")
if v is None:
  v = vals.get("avg")
print(float('nan') if v is None else float(v))
PY
)

  poll_sleep_ms_p95=$(python3 - <<PY
import json, math
p="$json_out"
with open(p) as f:
  j=json.load(f)
trend=j["metrics"].get("payrun_finalize_rabbit_poll_sleep_ms")
if not trend:
  print(float('nan'))
  raise SystemExit(0)
vals = trend.get("values") if isinstance(trend, dict) else None
if not vals:
  vals = trend
v = vals.get("p(95)")
if v is None:
  v = vals.get("max")
if v is None:
  v = vals.get("avg")
print(float('nan') if v is None else float(v))
PY
)

  poll_quant_ratio_p50=$(python3 - <<PY
import json, math
p="$json_out"
with open(p) as f:
  j=json.load(f)
trend=j["metrics"].get("payrun_finalize_rabbit_poll_quantization_ratio")
if not trend:
  print(float('nan'))
  raise SystemExit(0)
vals = trend.get("values") if isinstance(trend, dict) else None
if not vals:
  vals = trend
v = vals.get("med")
if v is None:
  v = vals.get("p(50)")
if v is None:
  v = vals.get("avg")
print(float('nan') if v is None else float(v))
PY
)

  poll_quant_ratio_p95=$(python3 - <<PY
import json, math
p="$json_out"
with open(p) as f:
  j=json.load(f)
trend=j["metrics"].get("payrun_finalize_rabbit_poll_quantization_ratio")
if not trend:
  print(float('nan'))
  raise SystemExit(0)
vals = trend.get("values") if isinstance(trend, dict) else None
if not vals:
  vals = trend
v = vals.get("p(95)")
if v is None:
  v = vals.get("max")
if v is None:
  v = vals.get("avg")
print(float('nan') if v is None else float(v))
PY
)

  server_elapsed_ms_p50=$(python3 - <<PY
import json, math
p="$json_out"
with open(p) as f:
  j=json.load(f)
trend=j["metrics"].get("payrun_finalize_rabbit_server_e2e_ms")
if not trend:
  print(float('nan'))
  raise SystemExit(0)
vals = trend.get("values") if isinstance(trend, dict) else None
if not vals:
  vals = trend
v = vals.get("med")
if v is None:
  v = vals.get("p(50)")
if v is None:
  v = vals.get("avg")
print(float('nan') if v is None else float(v))
PY
)

  server_elapsed_ms_p95=$(python3 - <<PY
import json, math
p="$json_out"
with open(p) as f:
  j=json.load(f)
trend=j["metrics"].get("payrun_finalize_rabbit_server_e2e_ms")
if not trend:
  print(float('nan'))
  raise SystemExit(0)
vals = trend.get("values") if isinstance(trend, dict) else None
if not vals:
  vals = trend
v = vals.get("p(95)")
if v is None:
  v = vals.get("max")
if v is None:
  v = vals.get("avg")
print(float('nan') if v is None else float(v))
PY
)

  server_eps_p50=$(python3 - <<PY
import json, math
p="$json_out"
with open(p) as f:
  j=json.load(f)
trend=j["metrics"].get("payrun_finalize_rabbit_server_employees_per_sec")
if not trend:
  print(float('nan'))
  raise SystemExit(0)
vals = trend.get("values") if isinstance(trend, dict) else None
if not vals:
  vals = trend
v = vals.get("med")
if v is None:
  v = vals.get("p(50)")
if v is None:
  v = vals.get("avg")
print(float('nan') if v is None else float(v))
PY
)

  server_eps_p95=$(python3 - <<PY
import json, math
p="$json_out"
with open(p) as f:
  j=json.load(f)
trend=j["metrics"].get("payrun_finalize_rabbit_server_employees_per_sec")
if not trend:
  print(float('nan'))
  raise SystemExit(0)
vals = trend.get("values") if isinstance(trend, dict) else None
if not vals:
  vals = trend
v = vals.get("p(95)")
if v is None:
  v = vals.get("min")
if v is None:
  v = vals.get("avg")
print(float('nan') if v is None else float(v))
PY
)

  echo "[bench] result workers=$workers p50_ms=$elapsed_ms_p50 p95_ms=$elapsed_ms_p95 server_p50_ms=$server_elapsed_ms_p50 p50_eps=$employees_per_sec_p50 polls_p50=$status_polls_p50 poll_quant_p50=$poll_quant_ratio_p50 db_cpu_max=$db_cpu_pct_max db_conns_max=$db_connections_max worker_avg_ms=$worker_finalize_avg_ms orch_avg_ms=$orchestrator_finalize_avg_ms"
  echo "$workers,$EMPLOYEE_COUNT,$TRIALS,$elapsed_ms_p50,$elapsed_ms_p95,$employees_per_sec_p50,$employees_per_sec_p95,$status_polls_p50,$status_polls_p95,$poll_sleep_ms_p50,$poll_sleep_ms_p95,$poll_quant_ratio_p50,$poll_quant_ratio_p95,$server_elapsed_ms_p50,$server_elapsed_ms_p95,$server_eps_p50,$server_eps_p95,$db_cpu_pct_max,$db_connections_max,$worker_finalize_avg_ms,$orchestrator_finalize_avg_ms,$run_id,$json_out,$metrics_csv" >> "$SUMMARY_CSV"
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
  echo "Results:"
  echo ""
  echo "workers | employee_count | elapsed_ms p50 | server_elapsed_ms p50 | employees/sec p50 | polls p50 | poll_quant p50 | db cpu% max | db conns max | worker avg ms | orch avg ms"
  echo "---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:"
  tail -n +2 "$SUMMARY_CSV" | while IFS=',' read -r w n t ms50 ms95 eps50 eps95 polls50 polls95 sleep50 sleep95 q50 q95 srv_ms50 srv_ms95 srv_eps50 srv_eps95 dbcpu dbconns wavg oavg rid art artm; do
    printf "%s | %s | %.0f | %.0f | %.2f | %.0f | %.2f | %s | %s | %s | %s\n" "$w" "$n" "$ms50" "$srv_ms50" "$eps50" "$polls50" "$q50" "$dbcpu" "$dbconns" "$wavg" "$oavg"
  done
  echo ""
  echo "Artifacts:"
  echo "- $SUMMARY_CSV"
  echo "- $OUT_DIR/k6-*.json (k6 summary exports)"
  echo "- $OUT_DIR/k6-*.txt (k6 logs)"
} > "$SUMMARY_MD"

echo "[bench] wrote: $SUMMARY_CSV"
echo "[bench] wrote: $SUMMARY_MD"
