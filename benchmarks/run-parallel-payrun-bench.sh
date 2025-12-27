#!/usr/bin/env bash
# Parallel payrun benchmark harness.
#
# This script runs k6 benchmarks against the orchestrator + worker services with varying worker replica counts.
# It collects Postgres statistics and service metrics to diagnose performance bottlenecks.
#
# Verbosity controls (to prevent terminal buffer issues):
# - BENCH_MINIMAL_OUTPUT=true : Disable all Postgres statistics collection (columns will be 'nan')
# - PG_DIAG_SAMPLE_EVERY=N    : Sample Postgres diagnostics every Nth sample (default: 3, 0=disabled)
# - PG_STAT_SAMPLE_EVERY=N    : Sample Postgres stats every Nth sample (default: same as PG_DIAG_SAMPLE_EVERY)
#
# Example:
#   BENCH_MINIMAL_OUTPUT=true ./benchmarks/run-parallel-payrun-bench.sh
#   PG_DIAG_SAMPLE_EVERY=10 ./benchmarks/run-parallel-payrun-bench.sh
#   ./benchmarks/run-parallel-payrun-bench.sh > /tmp/bench.log 2>&1 &

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

# ---- User-configurable env ----
ORCH_URL=${ORCH_URL:-http://localhost:8085}
EMPLOYER_ID=${EMPLOYER_ID:-EMP-BENCH}
PAY_PERIOD_ID=${PAY_PERIOD_ID:-2025-01-BW1}
CHECK_DATE=${CHECK_DATE:-2025-01-15}
START_DATE=${START_DATE:-2025-01-01}
END_DATE=${END_DATE:-2025-01-14}

# Optional Phase B (orchestrator): render statements / export artifacts after finalize
# These map to benchmarks/k6/orchestrator-rabbit-finalize.js env vars.
RENDER_AFTER_FINALIZE=${RENDER_AFTER_FINALIZE:-false}
BENCH_HEADER=${BENCH_HEADER:-X-Benchmark-Token}
BENCH_TOKEN=${BENCH_TOKEN:-${WORKER_BENCHMARKS_TOKEN:-dev-secret}}
RENDER_SERIALIZE_JSON=${RENDER_SERIALIZE_JSON:-true}
RENDER_GENERATE_CSV=${RENDER_GENERATE_CSV:-false}
RENDER_LIMIT=${RENDER_LIMIT:-}

# Verbosity controls
BENCH_MINIMAL_OUTPUT=${BENCH_MINIMAL_OUTPUT:-false}

EMPLOYEE_COUNT=${EMPLOYEE_COUNT:-1000}
EMPLOYEE_ID_PREFIX=${EMPLOYEE_ID_PREFIX:-EE-BENCH-}
EMPLOYEE_ID_START=${EMPLOYEE_ID_START:-1}
EMPLOYEE_ID_PAD=${EMPLOYEE_ID_PAD:-6}

# Seed knobs (match hr_seed.sql defaults)
MI_EVERY=${MI_EVERY:-5}
GARNISHMENT_EVERY=${GARNISHMENT_EVERY:-10}

# Compose files for parallel stack
COMPOSE_FILES=(
  -f "$ROOT_DIR/docker-compose.yml"
  -f "$ROOT_DIR/docker-compose.bench-ports.yml"
  -f "$ROOT_DIR/docker-compose.bench-parallel.yml"
  # Bench-only Postgres profiling (pg_stat_statements + extra stats views)
  -f "$ROOT_DIR/docker-compose.bench-postgres-statements.yml"
)

# PgBouncer connection pooling (enabled by default for Phase 2 scaling improvements)
# Set BENCH_DISABLE_PGBOUNCER=true to benchmark without connection pooling
BENCH_DISABLE_PGBOUNCER=${BENCH_DISABLE_PGBOUNCER:-false}
if [[ "$BENCH_DISABLE_PGBOUNCER" != "true" ]]; then
  COMPOSE_FILES+=(
    -f "$ROOT_DIR/docker-compose.pgbouncer.yml"
  )
  echo "[bench] PgBouncer connection pooling enabled (Phase 2)"
else
  echo "[bench] PgBouncer disabled (direct Postgres connections)"
fi

# Redis caching (Phase 3 optimization)
# Set BENCH_ENABLE_REDIS=true to enable Redis caching for tax/labor standards
BENCH_ENABLE_REDIS=${BENCH_ENABLE_REDIS:-false}
if [[ "$BENCH_ENABLE_REDIS" == "true" ]]; then
  COMPOSE_FILES+=(
    -f "$ROOT_DIR/docker-compose.redis.yml"
  )
  echo "[bench] Redis caching enabled (Phase 3)"
else
  echo "[bench] Redis caching disabled"
fi


# Optional: reset all benchmark data (drops Postgres volume and containers) before seeding.
# This prevents stale/partial seeds from skewing results.
# Defaults to true for clean, reproducible runs.
BENCH_RESET_DB=${BENCH_RESET_DB:-true}

# Bench-only A/B toggle: disable synchronous_commit to test whether we are WAL-sync bound.
# Valid values: on|off
BENCH_POSTGRES_SYNC_COMMIT=${BENCH_POSTGRES_SYNC_COMMIT:-on}
if [[ "$BENCH_POSTGRES_SYNC_COMMIT" == "off" ]]; then
  COMPOSE_FILES+=(
    -f "$ROOT_DIR/docker-compose.bench-postgres-sync-commit-off.yml"
  )
fi

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

# Internal JWT secret shared between worker/orchestrator for internal endpoints
DOWNSTREAMS_ORCHESTRATOR_INTERNAL_JWT_SECRET=${DOWNSTREAMS_ORCHESTRATOR_INTERNAL_JWT_SECRET:-dev-internal-token}
DOWNSTREAMS_ORCHESTRATOR_INTERNAL_JWT_KID=${DOWNSTREAMS_ORCHESTRATOR_INTERNAL_JWT_KID:-k1}

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

resolve_postgres_container() {
  docker ps \
    --filter "label=com.docker.compose.project=us-payroll-platform" \
    --filter "label=com.docker.compose.service=postgres" \
    --format '{{.Names}}' \
    | head -n 1
}

# ---- Preconditions ----
require_cmd docker
require_cmd k6
require_cmd awk
require_cmd sed
require_cmd python3

echo "[bench] out_dir=$OUT_DIR"
if [[ "$BENCH_MINIMAL_OUTPUT" == "true" ]]; then
  echo "[bench] minimal output mode enabled (Postgres stats disabled)"
fi

# ---- Bring up stack (idempotent) ----
# Export the secret so compose picks it up.
export DOWNSTREAMS_ORCHESTRATOR_INTERNAL_JWT_SECRET
export DOWNSTREAMS_ORCHESTRATOR_INTERNAL_JWT_KID

echo "[bench] starting stack (compose)"
# Optional full reset for clean seeds
if [[ "$BENCH_RESET_DB" == "true" ]]; then
  echo "[bench] resetting databases and services (docker compose down -v)"
  docker compose "${COMPOSE_FILES[@]}" down -v || true
fi

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

# Ensure Postgres is recreated when switching synchronous_commit mode.
if [[ "$BENCH_POSTGRES_SYNC_COMMIT" == "off" ]]; then
  echo "[bench] forcing postgres recreate (synchronous_commit=off)"
  docker compose "${COMPOSE_FILES[@]}" up -d --force-recreate postgres
fi

# ---- Bench-only DB instrumentation ----
# Ensure pg_stat_statements is created in the postgres database (requires shared_preload_libraries).
pg_container=$(resolve_postgres_container)
if [[ -z "${pg_container}" ]]; then
  echo "[bench] ERROR: could not resolve Postgres container (compose labels not found)." >&2
  exit 1
fi

# Wait for Postgres to accept connections (up to 30 seconds)
echo "[bench] waiting for Postgres to accept connections..."
for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  if docker exec "$pg_container" psql -U postgres -d postgres -c "SELECT 1;" >/dev/null 2>&1; then
    echo "[bench] Postgres ready after ${i} attempts"
    break
  fi
  sleep 2
done

docker exec "$pg_container" psql -U postgres -d postgres -v ON_ERROR_STOP=1 -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;" >/dev/null 2>&1 || true

# PgBouncer requires MD5 password encryption for auth_query.
# Check if password encryption is already MD5, otherwise reconfigure.
if [[ "$BENCH_DISABLE_PGBOUNCER" != "true" ]]; then
  current_enc=$(docker exec "$pg_container" psql -U postgres -d postgres -tAc "SHOW password_encryption;" | tr -d '[:space:]')
  
  if [[ "$current_enc" != "md5" ]]; then
    echo "[bench] configuring MD5 password encryption for PgBouncer compatibility"
    
    # Set password_encryption to md5
    docker exec "$pg_container" psql -U postgres -d postgres -c "ALTER SYSTEM SET password_encryption='md5';" >/dev/null 2>&1 || true
    
    # Restart Postgres to apply the setting
    echo "[bench] restarting Postgres to apply password_encryption=md5"
    docker compose "${COMPOSE_FILES[@]}" restart postgres >/dev/null 2>&1 || true
    
    # Wait for Postgres to fully shut down first
    echo "[bench] waiting for Postgres to shut down..."
    sleep 3
    
    # Wait for Postgres to come back up (up to 60 seconds)
    echo "[bench] waiting for Postgres to start up..."
    postgres_ready=false
    for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30; do
      if docker exec "$pg_container" psql -U postgres -d postgres -c "SELECT 1;" >/dev/null 2>&1; then
        echo "[bench] Postgres ready after ${i} attempts"
        postgres_ready=true
        break
      fi
      sleep 2
    done
    
    if [[ "$postgres_ready" != "true" ]]; then
      echo "[bench] ERROR: Postgres did not start up after 60 seconds" >&2
      docker logs "$pg_container" 2>&1 | tail -50 >&2
      exit 1
    fi
    
    # Verify password encryption is now MD5
    current_enc=$(docker exec "$pg_container" psql -U postgres -d postgres -tAc "SHOW password_encryption;" | tr -d '[:space:]')
    echo "[bench] password_encryption now set to: $current_enc"
    
    echo "[bench] resetting service user passwords with MD5 encryption"
    # Now that password_encryption=md5, recreate all passwords
    for u in postgres hr_service tax_service labor_service orchestrator_service time_service; do
      docker exec "$pg_container" psql -U postgres -d postgres -c "ALTER USER $u WITH PASSWORD '$u';" >/dev/null 2>&1 || true
    done
    
    # Restart all services to pick up new password hashes
    echo "[bench] restarting services for MD5 password authentication"
    docker compose "${COMPOSE_FILES[@]}" restart >/dev/null 2>&1 || true
    sleep 5
  else
    echo "[bench] password encryption already set to MD5"
  fi
fi

# ---- Wait for schemas to exist (Flyway via service startup) ----
wait_for_service_health() {
  local service_name="$1"
  local port="$2"
  local tries=60
  echo "[bench] waiting for $service_name health endpoint (max ${tries} retries)..."
  while [[ $tries -gt 0 ]]; do
    if curl -sf "http://localhost:${port}/actuator/health" >/dev/null 2>&1; then
      echo "[bench] $service_name is healthy"
      return 0
    fi
    sleep 2
    tries=$((tries-1))
  done
  echo "[bench] ERROR: $service_name did not become healthy" >&2
  return 1
}

wait_for_table() {
  local db="$1"
  local table="$2"
  local tries=30  # Reduced since we already waited for service health
  echo "[bench] waiting for table $table in $db (max ${tries} retries)..."
  # Extract just the table name without schema for grep (to_regclass returns just the table name)
  local table_name="${table##*.}"
  while [[ $tries -gt 0 ]]; do
    local result
    result=$(docker exec "$pg_container" psql -U postgres -d "$db" -tAc "select to_regclass('$table')" 2>/dev/null)
    if [[ -n "$result" ]] && [[ "$result" != "" ]] && ! grep -q "does not exist" <<<"$result"; then
      echo "[bench] table $table found in $db (result: $result)"
      return 0
    fi
    sleep 2
    tries=$((tries-1))
  done
  echo "[bench] ERROR: table $table not found in $db after waiting" >&2
  echo "[bench] DEBUG: checking database..." >&2
  docker exec "$pg_container" psql -U postgres -d "$db" -c "\dt" 2>&1 | tail -20 >&2
  exit 1
}

echo "[bench] waiting for services to become healthy"
wait_for_service_health "hr-service" 8081
wait_for_service_health "tax-service" 8082
wait_for_service_health "labor-service" 8083
wait_for_service_health "orchestrator-service" 8085

echo "[bench] waiting for Flyway migrations to finish (hr/tax/labor)"
wait_for_table us_payroll_hr public.employee_profile_effective
wait_for_table us_payroll_tax public.tax_rule
wait_for_table us_payroll_labor public.labor_standard

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
echo "workers,employee_count,trials,elapsed_ms_p50,elapsed_ms_p95,employees_per_sec_p50,employees_per_sec_p95,status_polls_p50,status_polls_p95,poll_sleep_ms_p50,poll_sleep_ms_p95,poll_quantization_ratio_p50,poll_quantization_ratio_p95,server_elapsed_ms_p50,server_elapsed_ms_p95,server_employees_per_sec_p50,server_employees_per_sec_p95,db_cpu_pct_max,db_connections_max,worker_finalize_avg_ms,orchestrator_finalize_avg_ms,pg_bgwriter_checkpoints_timed_delta,pg_bgwriter_checkpoints_req_delta,pg_bgwriter_checkpoint_write_time_ms_delta,pg_bgwriter_checkpoint_sync_time_ms_delta,pg_bgwriter_buffers_checkpoint_delta,pg_bgwriter_buffers_clean_delta,pg_bgwriter_maxwritten_clean_delta,pg_bgwriter_buffers_backend_delta,pg_bgwriter_buffers_backend_fsync_delta,pg_bgwriter_buffers_alloc_delta,pg_wal_records_delta,pg_wal_fpi_delta,pg_wal_bytes_delta,pg_wal_buffers_full_delta,pg_wal_write_delta,pg_wal_sync_delta,pg_wal_write_time_ms_delta,pg_wal_sync_time_ms_delta,pg_statements_end_queryid,pg_statements_end_query_snippet,pg_statements_queryid_stable,pg_statements_calls_delta,pg_statements_total_exec_time_ms_delta,pg_statements_end_mean_exec_time_ms,run_id,artifact_json,artifact_metrics_csv" > "$SUMMARY_CSV"

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

  echo "ts_epoch,postgres_cpu_pct,postgres_connections,postgres_top_wait_event,postgres_top_wait_count,postgres_waiting_active_count,postgres_locks_granted,postgres_locks_waiting,postgres_top_lock_wait_mode,postgres_top_lock_wait_count,pg_bgwriter_checkpoints_timed,pg_bgwriter_checkpoints_req,pg_bgwriter_checkpoint_write_time_ms,pg_bgwriter_checkpoint_sync_time_ms,pg_bgwriter_buffers_checkpoint,pg_bgwriter_buffers_clean,pg_bgwriter_maxwritten_clean,pg_bgwriter_buffers_backend,pg_bgwriter_buffers_backend_fsync,pg_bgwriter_buffers_alloc,pg_wal_records,pg_wal_fpi,pg_wal_bytes,pg_wal_buffers_full,pg_wal_write,pg_wal_sync,pg_wal_write_time_ms,pg_wal_sync_time_ms,pg_statements_top_queryid,pg_statements_top_calls,pg_statements_top_total_exec_time_ms,pg_statements_top_mean_exec_time_ms,pg_statements_top_query_snippet" > "$metrics_csv"

  pg_container=$(resolve_postgres_container)
  if [[ -z "${pg_container}" ]]; then
    echo "[bench] ERROR: could not resolve Postgres container (compose labels not found)." >&2
    exit 1
  fi

# Control how often we run heavier Postgres diagnostic queries.
  # The sampler loop sleeps 5s; default 3 => run diagnostics every ~15s.
  # Set to 0 to disable diagnostics (reduces output verbosity).
  PG_DIAG_SAMPLE_EVERY=${PG_DIAG_SAMPLE_EVERY:-3}

  # Additional sampling for DB internal counters can be slightly more expensive; default to same cadence as diagnostics.
  # Set to 0 to disable stats sampling (reduces output verbosity).
  PG_STAT_SAMPLE_EVERY=${PG_STAT_SAMPLE_EVERY:-$PG_DIAG_SAMPLE_EVERY}

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

  # Reset pg_stat_statements between runs so the "top SQL" snapshot is easier to interpret.
  docker exec "$pg_container" psql -U postgres -d postgres -c "SELECT pg_stat_statements_reset();" >/dev/null 2>&1 || true

  # Start background DB sampler
  (
    # Do not let optional sampling queries (e.g. pg_stat_statements when not enabled)
    # abort the entire sampler loop.
    set +e
    set +o pipefail

    i=0
    while true; do
      ts=$(date +%s)
      cpu=$(docker stats --no-stream --format '{{.CPUPerc}}' "$pg_container" 2>/dev/null | tr -d '%')
      conns=$(docker exec "$pg_container" psql -U postgres -d postgres -t -A -c "SELECT count(*) FROM pg_stat_activity;" 2>/dev/null | tr -d '[:space:]')

      # Default empty diagnostic fields; filled periodically.
      top_wait_event=""
      top_wait_count=""
      waiting_active_count=""
      locks_granted=""
      locks_waiting=""
      top_lock_wait_mode=""
      top_lock_wait_count=""

      # pg_stat_bgwriter
      bg_checkpoints_timed=""
      bg_checkpoints_req=""
      bg_checkpoint_write_time_ms=""
      bg_checkpoint_sync_time_ms=""
      bg_buffers_checkpoint=""
      bg_buffers_clean=""
      bg_maxwritten_clean=""
      bg_buffers_backend=""
      bg_buffers_backend_fsync=""
      bg_buffers_alloc=""

      # pg_stat_wal
      wal_records=""
      wal_fpi=""
      wal_bytes=""
      wal_buffers_full=""
      wal_write=""
      wal_sync=""
      wal_write_time_ms=""
      wal_sync_time_ms=""

      # pg_stat_statements (if enabled)
      pss_top_queryid=""
      pss_top_calls=""
      pss_top_total_exec_time_ms=""
      pss_top_mean_exec_time_ms=""
      pss_top_query_snippet=""

      i=$((i + 1))
      diag_ok=false
      stat_ok=false
      # Skip all Postgres stats in minimal mode
      if [[ "$BENCH_MINIMAL_OUTPUT" != "true" ]]; then
        if [[ "$PG_DIAG_SAMPLE_EVERY" =~ ^[0-9]+$ ]] && [[ "$PG_DIAG_SAMPLE_EVERY" -gt 0 ]] && (( i % PG_DIAG_SAMPLE_EVERY == 0 )); then
          diag_ok=true
        fi
        if [[ "$PG_STAT_SAMPLE_EVERY" =~ ^[0-9]+$ ]] && [[ "$PG_STAT_SAMPLE_EVERY" -gt 0 ]] && (( i % PG_STAT_SAMPLE_EVERY == 0 )); then
          stat_ok=true
        fi
      fi

      if [[ "$diag_ok" == "true" ]]; then
        # Top active wait event (if any)
        wait_line=$(docker exec "$pg_container" psql -U postgres -d postgres -t -A -F '|' -c \
          "SELECT (coalesce(wait_event_type,'NONE') || '/' || coalesce(wait_event,'NONE')) AS ev, count(*) \
           FROM pg_stat_activity \
           WHERE wait_event_type IS NOT NULL AND state <> 'idle' \
           GROUP BY ev \
           ORDER BY count(*) DESC \
           LIMIT 1;" 2>/dev/null | tr -d '[:space:]')

        if [[ -n "$wait_line" ]]; then
          top_wait_event=$(printf '%s' "$wait_line" | cut -d'|' -f1)
          top_wait_count=$(printf '%s' "$wait_line" | cut -d'|' -f2)
        else
          top_wait_event="NONE"
          top_wait_count="0"
        fi

        waiting_active_count=$(docker exec "$pg_container" psql -U postgres -d postgres -t -A -c \
          "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type IS NOT NULL AND state <> 'idle';" 2>/dev/null | tr -d '[:space:]')

        locks_line=$(docker exec "$pg_container" psql -U postgres -d postgres -t -A -F '|' -c \
          "SELECT coalesce(sum(CASE WHEN granted THEN 1 ELSE 0 END),0) AS granted, \
                  coalesce(sum(CASE WHEN NOT granted THEN 1 ELSE 0 END),0) AS waiting \
           FROM pg_locks;" 2>/dev/null | tr -d '[:space:]')

        if [[ -n "$locks_line" ]]; then
          locks_granted=$(printf '%s' "$locks_line" | cut -d'|' -f1)
          locks_waiting=$(printf '%s' "$locks_line" | cut -d'|' -f2)
        else
          locks_granted="0"
          locks_waiting="0"
        fi

        lock_wait_line=$(docker exec "$pg_container" psql -U postgres -d postgres -t -A -F '|' -c \
          "SELECT mode, count(*) \
           FROM pg_locks \
           WHERE NOT granted \
           GROUP BY mode \
           ORDER BY count(*) DESC \
           LIMIT 1;" 2>/dev/null | tr -d '[:space:]')

        if [[ -n "$lock_wait_line" ]]; then
          top_lock_wait_mode=$(printf '%s' "$lock_wait_line" | cut -d'|' -f1)
          top_lock_wait_count=$(printf '%s' "$lock_wait_line" | cut -d'|' -f2)
        else
          top_lock_wait_mode="NONE"
          top_lock_wait_count="0"
        fi
      fi

      if [[ "$stat_ok" == "true" ]]; then
        # ---- pg_stat_bgwriter counters ----
        bg_line=$(docker exec "$pg_container" psql -U postgres -d postgres -t -A -F '|' -c \
          "SELECT checkpoints_timed, checkpoints_req, checkpoint_write_time, checkpoint_sync_time, \
                  buffers_checkpoint, buffers_clean, maxwritten_clean, buffers_backend, buffers_backend_fsync, buffers_alloc \
           FROM pg_stat_bgwriter;" 2>/dev/null | tr -d '\r' | head -n 1)

        if [[ -n "$bg_line" ]]; then
          bg_checkpoints_timed=$(printf '%s' "$bg_line" | cut -d'|' -f1)
          bg_checkpoints_req=$(printf '%s' "$bg_line" | cut -d'|' -f2)
          bg_checkpoint_write_time_ms=$(printf '%s' "$bg_line" | cut -d'|' -f3)
          bg_checkpoint_sync_time_ms=$(printf '%s' "$bg_line" | cut -d'|' -f4)
          bg_buffers_checkpoint=$(printf '%s' "$bg_line" | cut -d'|' -f5)
          bg_buffers_clean=$(printf '%s' "$bg_line" | cut -d'|' -f6)
          bg_maxwritten_clean=$(printf '%s' "$bg_line" | cut -d'|' -f7)
          bg_buffers_backend=$(printf '%s' "$bg_line" | cut -d'|' -f8)
          bg_buffers_backend_fsync=$(printf '%s' "$bg_line" | cut -d'|' -f9)
          bg_buffers_alloc=$(printf '%s' "$bg_line" | cut -d'|' -f10)
        fi

        # ---- pg_stat_wal counters (Postgres 14+) ----
        wal_line=$(docker exec "$pg_container" psql -U postgres -d postgres -t -A -F '|' -c \
          "SELECT wal_records, wal_fpi, wal_bytes, wal_buffers_full, wal_write, wal_sync, wal_write_time, wal_sync_time \
           FROM pg_stat_wal;" 2>/dev/null | tr -d '\r' | head -n 1)

        if [[ -n "$wal_line" ]]; then
          wal_records=$(printf '%s' "$wal_line" | cut -d'|' -f1)
          wal_fpi=$(printf '%s' "$wal_line" | cut -d'|' -f2)
          wal_bytes=$(printf '%s' "$wal_line" | cut -d'|' -f3)
          wal_buffers_full=$(printf '%s' "$wal_line" | cut -d'|' -f4)
          wal_write=$(printf '%s' "$wal_line" | cut -d'|' -f5)
          wal_sync=$(printf '%s' "$wal_line" | cut -d'|' -f6)
          wal_write_time_ms=$(printf '%s' "$wal_line" | cut -d'|' -f7)
          wal_sync_time_ms=$(printf '%s' "$wal_line" | cut -d'|' -f8)
        fi

        # ---- pg_stat_statements: take a small top-1 snapshot by total_exec_time ----
        # If the extension isn't enabled, the query will fail and we leave fields blank.
        pss_line=$(docker exec "$pg_container" psql -U postgres -d postgres -t -A -F '|' -c \
          "SELECT queryid, calls, total_exec_time, mean_exec_time, left(regexp_replace(query, E'[\\n\\r\\t ]+', ' ', 'g'), 120) \
           FROM pg_stat_statements \
           ORDER BY total_exec_time DESC \
           LIMIT 1;" 2>/dev/null | tr -d '\r' | head -n 1)

        if [[ -n "$pss_line" ]]; then
          pss_top_queryid=$(printf '%s' "$pss_line" | cut -d'|' -f1)
          pss_top_calls=$(printf '%s' "$pss_line" | cut -d'|' -f2)
          pss_top_total_exec_time_ms=$(printf '%s' "$pss_line" | cut -d'|' -f3)
          pss_top_mean_exec_time_ms=$(printf '%s' "$pss_line" | cut -d'|' -f4)
          pss_top_query_snippet=$(printf '%s' "$pss_line" | cut -d'|' -f5)
          # Keep CSV stable: strip commas/quotes/pipes from the snippet.
          pss_top_query_snippet=$(printf '%s' "$pss_top_query_snippet" | sed 's/,/;/g; s/"/\x27/g; s/|/ /g')
          # Truncate to 80 chars to prevent terminal buffer issues
          pss_top_query_snippet=$(printf '%s' "$pss_top_query_snippet" | cut -c1-80)
        fi
      fi

      cpu=${cpu:-}
      conns=${conns:-}
      waiting_active_count=${waiting_active_count:-}
      locks_granted=${locks_granted:-}
      locks_waiting=${locks_waiting:-}

      echo "$ts,$cpu,$conns,$top_wait_event,$top_wait_count,$waiting_active_count,$locks_granted,$locks_waiting,$top_lock_wait_mode,$top_lock_wait_count,$bg_checkpoints_timed,$bg_checkpoints_req,$bg_checkpoint_write_time_ms,$bg_checkpoint_sync_time_ms,$bg_buffers_checkpoint,$bg_buffers_clean,$bg_maxwritten_clean,$bg_buffers_backend,$bg_buffers_backend_fsync,$bg_buffers_alloc,$wal_records,$wal_fpi,$wal_bytes,$wal_buffers_full,$wal_write,$wal_sync,$wal_write_time_ms,$wal_sync_time_ms,$pss_top_queryid,$pss_top_calls,$pss_top_total_exec_time_ms,$pss_top_mean_exec_time_ms,$pss_top_query_snippet" >> "$metrics_csv"
      sleep 5
    done
  ) &
  metrics_pid=$!

  # Set bench_start_time before starting progress monitor
  bench_start_time=$(date +%s)

  # Start progress monitor to show payrun completion status by querying database
  (
    sleep 10  # Give k6 time to start and create payrun
    
    while true; do
      # Query pay_run_item table directly for progress
      progress=$(docker exec "$pg_container" psql -U postgres -d us_payroll_orchestrator -t -A -F '|' -c \
        "SELECT \
           COUNT(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded, \
           COUNT(*) FILTER (WHERE status = 'FAILED') AS failed, \
           COUNT(*) FILTER (WHERE status = 'RUNNING') AS running, \
           COUNT(*) FILTER (WHERE status = 'QUEUED') AS queued, \
           COUNT(*) AS total \
         FROM pay_run_item \
         WHERE employer_id = '$EMPLOYER_ID';" 2>/dev/null | tr -d '[:space:]')
      
      if [[ -n "$progress" ]] && [[ "$progress" != "0|0|0|0|0" ]]; then
        succeeded=$(echo "$progress" | cut -d'|' -f1)
        failed=$(echo "$progress" | cut -d'|' -f2)
        running=$(echo "$progress" | cut -d'|' -f3)
        queued=$(echo "$progress" | cut -d'|' -f4)
        total=$(echo "$progress" | cut -d'|' -f5)
        
        if [[ "$total" -gt 0 ]]; then
          pct=$(python3 -c "print(f'{($succeeded * 100.0 / $total):.1f}')")
          rate=$(python3 -c "print(f'{($succeeded / (($(date +%s) - $bench_start_time) or 1)):.1f}')" 2>/dev/null || echo "?")
          echo "[bench] Progress: $succeeded/$total ($pct%) | Running: $running | Queued: $queued | Failed: $failed | Rate: ${rate}/s"
        fi
      fi
      
      sleep 5
    done
  ) &
  progress_pid=$!

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
    -e RENDER_AFTER_FINALIZE="$RENDER_AFTER_FINALIZE" \
    -e BENCH_HEADER="$BENCH_HEADER" \
    -e BENCH_TOKEN="$BENCH_TOKEN" \
    -e RENDER_SERIALIZE_JSON="$RENDER_SERIALIZE_JSON" \
    -e RENDER_GENERATE_CSV="$RENDER_GENERATE_CSV" \
    -e RENDER_LIMIT="$RENDER_LIMIT" \
    --summary-export "$json_out" \
    "$ROOT_DIR/benchmarks/k6/orchestrator-rabbit-finalize.js" \
    2>&1 | tee "$txt_out"
  k6_exit=${PIPESTATUS[0]}
  set -e

  # Stop progress monitor and metrics sampler
  kill "$progress_pid" >/dev/null 2>&1 || true
  wait "$progress_pid" >/dev/null 2>&1 || true
  kill "$metrics_pid" >/dev/null 2>&1 || true
  wait "$metrics_pid" >/dev/null 2>&1 || true

  if [[ $k6_exit -ne 0 ]]; then
    echo "[bench] k6 failed for workers=$workers (exit=$k6_exit). See $txt_out" >&2
    continue
  fi

  # ---- Export paychecks per payRunId (CSV + JSON) ----
  # The k6 script logs PAYRUN_ID=<id> once per iteration.
  # k6 logs PAYRUN_ID inside quoted log fields; extract only safe identifier chars so we don't capture trailing quotes.
  mapfile -t payrun_ids < <(grep -Eo 'PAYRUN_ID=[A-Za-z0-9:-]+' "$txt_out" | sed 's/^PAYRUN_ID=//' | tr -d '\r' | awk 'NF{print $0}' | tail -n +1)

  if [[ ${#payrun_ids[@]} -eq 0 ]]; then
    echo "[bench] WARN: no PAYRUN_ID lines found in $txt_out; skipping paycheck export"
  else
    for payrun_id in "${payrun_ids[@]}"; do
      # Defensive filename sanitization.
      safe_payrun_id=$(printf '%s' "$payrun_id" | sed 's/[^A-Za-z0-9_.:-]/_/g')
      paychecks_csv="$OUT_DIR/paychecks-${run_id}-${safe_payrun_id}.csv"
      paychecks_json="$OUT_DIR/paychecks-${run_id}-${safe_payrun_id}.json"

      echo "[bench] exporting paychecks employer=$EMPLOYER_ID payRunId=$payrun_id -> $(basename "$paychecks_csv")"

      # Escape values for SQL single-quoted literals.
      employer_sql=$(printf '%s' "$EMPLOYER_ID" | sed "s/'/''/g")
      pay_run_sql=$(printf '%s' "$payrun_id" | sed "s/'/''/g")

      # CSV export (one row per pay_run_item; paycheck columns NULL if not SUCCEEDED)
      docker exec -i "$pg_container" psql -U postgres -d us_payroll_orchestrator -v ON_ERROR_STOP=1 > "$paychecks_csv" <<SQL
COPY (
  SELECT
    pri.employer_id,
    pri.pay_run_id,
    pri.employee_id,
    pri.status        AS item_status,
    pri.attempt_count,
    pri.last_error,
    pri.paycheck_id,
    p.pay_period_id,
    p.check_date,
    p.currency,
    p.gross_cents,
    p.net_cents,
    p.payload_json
  FROM pay_run_item pri
  LEFT JOIN paycheck p
    ON p.employer_id = pri.employer_id
   AND p.paycheck_id = pri.paycheck_id
  WHERE pri.employer_id = '$employer_sql'
    AND pri.pay_run_id  = '$pay_run_sql'
  ORDER BY pri.employee_id
) TO STDOUT WITH (FORMAT csv, HEADER true);
SQL

      # JSON export (array of objects; same shape as CSV)
      docker exec -i "$pg_container" psql -U postgres -d us_payroll_orchestrator -v ON_ERROR_STOP=1 -t -A > "$paychecks_json" <<SQL
SELECT COALESCE(json_agg(row_to_json(t)), '[]'::json)
FROM (
  SELECT
    pri.employer_id,
    pri.pay_run_id,
    pri.employee_id,
    pri.status        AS item_status,
    pri.attempt_count,
    pri.last_error,
    pri.paycheck_id,
    p.pay_period_id,
    p.check_date,
    p.currency,
    p.gross_cents,
    p.net_cents,
    p.payload_json
  FROM pay_run_item pri
  LEFT JOIN paycheck p
    ON p.employer_id = pri.employer_id
   AND p.paycheck_id = pri.paycheck_id
  WHERE pri.employer_id = '$employer_sql'
    AND pri.pay_run_id  = '$pay_run_sql'
  ORDER BY pri.employee_id
) t;
SQL
    done
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

  # ---- Per-run deltas for cumulative Postgres counters (end-start) ----
  # Initialize all delta variables to nan (overwritten below if stats are collected)
  # These are sampled into metrics_csv periodically; we compute deltas so attribution is visible in summary.csv.
  bg_checkpoints_timed_delta="nan"
  bg_checkpoints_req_delta="nan"
  bg_checkpoint_write_time_ms_delta="nan"
  bg_checkpoint_sync_time_ms_delta="nan"
  bg_buffers_checkpoint_delta="nan"
  bg_buffers_clean_delta="nan"
  bg_maxwritten_clean_delta="nan"
  bg_buffers_backend_delta="nan"
  bg_buffers_backend_fsync_delta="nan"
  bg_buffers_alloc_delta="nan"
  wal_records_delta="nan"
  wal_fpi_delta="nan"
  wal_bytes_delta="nan"
  wal_buffers_full_delta="nan"
  wal_write_delta="nan"
  wal_sync_delta="nan"
  wal_write_time_ms_delta="nan"
  wal_sync_time_ms_delta="nan"
  pss_end_queryid=""
  pss_end_query_snippet=""
  pss_queryid_stable="false"
  pss_calls_delta="nan"
  pss_total_exec_time_ms_delta="nan"
  pss_end_mean_exec_time_ms="nan"

  # Only compute deltas if not in minimal mode
  if [[ "$BENCH_MINIMAL_OUTPUT" != "true" ]]; then
    read -r \
    bg_checkpoints_timed_delta \
    bg_checkpoints_req_delta \
    bg_checkpoint_write_time_ms_delta \
    bg_checkpoint_sync_time_ms_delta \
    bg_buffers_checkpoint_delta \
    bg_buffers_clean_delta \
    bg_maxwritten_clean_delta \
    bg_buffers_backend_delta \
    bg_buffers_backend_fsync_delta \
    bg_buffers_alloc_delta \
    wal_records_delta \
    wal_fpi_delta \
    wal_bytes_delta \
    wal_buffers_full_delta \
    wal_write_delta \
    wal_sync_delta \
    wal_write_time_ms_delta \
    wal_sync_time_ms_delta \
    pss_end_queryid \
    pss_end_query_snippet \
    pss_queryid_stable \
    pss_calls_delta \
    pss_total_exec_time_ms_delta \
    pss_end_mean_exec_time_ms \
    < <(
      python3 - <<PY
import csv
import math

path = r"$metrics_csv"

FIELDS_BG = [
  'pg_bgwriter_checkpoints_timed',
  'pg_bgwriter_checkpoints_req',
  'pg_bgwriter_checkpoint_write_time_ms',
  'pg_bgwriter_checkpoint_sync_time_ms',
  'pg_bgwriter_buffers_checkpoint',
  'pg_bgwriter_buffers_clean',
  'pg_bgwriter_maxwritten_clean',
  'pg_bgwriter_buffers_backend',
  'pg_bgwriter_buffers_backend_fsync',
  'pg_bgwriter_buffers_alloc',
]

FIELDS_WAL = [
  'pg_wal_records',
  'pg_wal_fpi',
  'pg_wal_bytes',
  'pg_wal_buffers_full',
  'pg_wal_write',
  'pg_wal_sync',
  'pg_wal_write_time_ms',
  'pg_wal_sync_time_ms',
]

def to_float(x):
  if x is None: return None
  s = str(x).strip()
  if s == '': return None
  try:
    return float(s)
  except Exception:
    return None

rows = []
with open(path) as f:
  r = csv.DictReader(f)
  for row in r:
    rows.append(row)

# Find first/last numeric sample for each field.
def first_last_numeric(field):
  first = None
  last = None
  for row in rows:
    v = to_float(row.get(field))
    if v is None:
      continue
    if first is None:
      first = v
    last = v
  return first, last

out = []
for field in FIELDS_BG + FIELDS_WAL:
  a,b = first_last_numeric(field)
  if a is None or b is None:
    out.append('nan')
  else:
    # integer-ish counters are safe as floats here (these values are not huge in this harness).
    out.append(str(b - a))

# pg_stat_statements: only compute delta if queryid is stable.
pss_queryid_first = None
pss_queryid_last = None
pss_first_row = None
pss_last_row = None

for row in rows:
  qid = (row.get('pg_statements_top_queryid') or '').strip()
  if qid:
    if pss_queryid_first is None:
      pss_queryid_first = qid
      pss_first_row = row
    pss_queryid_last = qid
    pss_last_row = row

pss_end_queryid = pss_queryid_last or ''
pss_end_query_snippet = (pss_last_row.get('pg_statements_top_query_snippet') if pss_last_row else '') or ''
pss_end_query_snippet = pss_end_query_snippet.replace(' ', '_')

stable = (pss_queryid_first is not None and pss_queryid_last is not None and pss_queryid_first == pss_queryid_last)

pss_calls_delta = 'nan'
pss_total_exec_time_delta = 'nan'
pss_end_mean_exec_time = 'nan'

if stable and pss_first_row and pss_last_row:
  c0 = to_float(pss_first_row.get('pg_statements_top_calls'))
  c1 = to_float(pss_last_row.get('pg_statements_top_calls'))
  t0 = to_float(pss_first_row.get('pg_statements_top_total_exec_time_ms'))
  t1 = to_float(pss_last_row.get('pg_statements_top_total_exec_time_ms'))
  m1 = to_float(pss_last_row.get('pg_statements_top_mean_exec_time_ms'))

  if c0 is not None and c1 is not None:
    pss_calls_delta = str(c1 - c0)
  if t0 is not None and t1 is not None:
    pss_total_exec_time_delta = str(t1 - t0)
  if m1 is not None:
    pss_end_mean_exec_time = str(m1)

# Append pg_stat_statements fields
out.append(pss_end_queryid)
out.append(pss_end_query_snippet if pss_end_query_snippet else '')
out.append('true' if stable else 'false')
out.append(pss_calls_delta)
out.append(pss_total_exec_time_delta)
out.append(pss_end_mean_exec_time)

print(' '.join(out))
PY
    )
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

  echo "[bench] result workers=$workers p50_ms=$elapsed_ms_p50 p95_ms=$elapsed_ms_p95 server_p50_ms=$server_elapsed_ms_p50 p50_eps=$employees_per_sec_p50 polls_p50=$status_polls_p50 poll_quant_p50=$poll_quant_ratio_p50 db_cpu_max=$db_cpu_pct_max db_conns_max=$db_connections_max worker_avg_ms=$worker_finalize_avg_ms orch_avg_ms=$orchestrator_finalize_avg_ms wal_bytes_delta=$wal_bytes_delta wal_sync_time_ms_delta=$wal_sync_time_ms_delta"
  echo "$workers,$EMPLOYEE_COUNT,$TRIALS,$elapsed_ms_p50,$elapsed_ms_p95,$employees_per_sec_p50,$employees_per_sec_p95,$status_polls_p50,$status_polls_p95,$poll_sleep_ms_p50,$poll_sleep_ms_p95,$poll_quant_ratio_p50,$poll_quant_ratio_p95,$server_elapsed_ms_p50,$server_elapsed_ms_p95,$server_eps_p50,$server_eps_p95,$db_cpu_pct_max,$db_connections_max,$worker_finalize_avg_ms,$orchestrator_finalize_avg_ms,$bg_checkpoints_timed_delta,$bg_checkpoints_req_delta,$bg_checkpoint_write_time_ms_delta,$bg_checkpoint_sync_time_ms_delta,$bg_buffers_checkpoint_delta,$bg_buffers_clean_delta,$bg_maxwritten_clean_delta,$bg_buffers_backend_delta,$bg_buffers_backend_fsync_delta,$bg_buffers_alloc_delta,$wal_records_delta,$wal_fpi_delta,$wal_bytes_delta,$wal_buffers_full_delta,$wal_write_delta,$wal_sync_delta,$wal_write_time_ms_delta,$wal_sync_time_ms_delta,$pss_end_queryid,$pss_end_query_snippet,$pss_queryid_stable,$pss_calls_delta,$pss_total_exec_time_ms_delta,$pss_end_mean_exec_time_ms,$run_id,$json_out,$metrics_csv" >> "$SUMMARY_CSV"
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
  tail -n +2 "$SUMMARY_CSV" | while IFS=',' read -r \
    w n t ms50 ms95 eps50 eps95 polls50 polls95 sleep50 sleep95 q50 q95 \
    srv_ms50 srv_ms95 srv_eps50 srv_eps95 dbcpu dbconns wavg oavg \
    bg_ct_d bg_cr_d bg_cwt_d bg_cst_d bg_buf_ckpt_d bg_buf_clean_d bg_maxw_clean_d bg_buf_backend_d bg_buf_backend_fsync_d bg_buf_alloc_d \
    wal_rec_d wal_fpi_d wal_bytes_d wal_buf_full_d wal_write_d wal_sync_d wal_write_time_d wal_sync_time_d \
    pss_qid pss_snip pss_stable pss_calls_d pss_total_time_d pss_mean_end \
    rid art artm; do
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
