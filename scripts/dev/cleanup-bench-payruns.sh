#!/usr/bin/env bash
set -euo pipefail

# Cleanup helper for benchmark/dev environments.
#
# What it does (scoped + guarded):
# - Finds RUNNING payruns for a single employer in the orchestrator DB.
# - (Optionally) deletes their pay_run_item rows and the pay_run row (RUNNING only).
# - (Optionally) deletes associated RABBIT outbox entries by matching payRunId in payload_json.
# - (Optionally) purges Rabbit retry/DLQ queues for finalize-employee jobs.
#
# Safety / guardrails:
# - Employer-scoped (EMPLOYER_ID)
# - Deletes only pay_run rows where status='RUNNING'
# - Defaults to DRY_RUN=true (no deletes/purges)
#
# Usage:
#   # Preview what would be deleted/purged
#   EMPLOYER_ID=EMP-BENCH ./scripts/dev/cleanup-bench-payruns.sh
#
#   # Apply cleanup
#   EMPLOYER_ID=EMP-BENCH APPLY=true ./scripts/dev/cleanup-bench-payruns.sh
#
# Options (env vars):
#   EMPLOYER_ID=EMP-BENCH
#   APPLY=true|false              (default false)
#   MIN_AGE_SECONDS=600           (default 600) only delete RUNNING runs older than this (based on finalize_started_at)
#   DELETE_OUTBOX_RABBIT=true     (default true)
#   PURGE_RABBIT=true             (default true)
#   PURGE_RETRY_QUEUES=true       (default true)
#   PURGE_DLQ=true                (default true)
#   PURGE_MAIN_QUEUE=false        (default false)
#
# DB connection (via docker exec into postgres container):
#   ORCH_DB=us_payroll_orchestrator
#   ORCH_DB_USER=orchestrator_service
#   ORCH_DB_PASSWORD=orchestrator_service

EMPLOYER_ID=${EMPLOYER_ID:-EMP-BENCH}
APPLY=${APPLY:-false}
MIN_AGE_SECONDS=${MIN_AGE_SECONDS:-600}

ORCH_DB=${ORCH_DB:-us_payroll_orchestrator}
ORCH_DB_USER=${ORCH_DB_USER:-orchestrator_service}
ORCH_DB_PASSWORD=${ORCH_DB_PASSWORD:-orchestrator_service}

DELETE_OUTBOX_RABBIT=${DELETE_OUTBOX_RABBIT:-true}

PURGE_RABBIT=${PURGE_RABBIT:-true}
PURGE_RETRY_QUEUES=${PURGE_RETRY_QUEUES:-true}
PURGE_DLQ=${PURGE_DLQ:-true}
PURGE_MAIN_QUEUE=${PURGE_MAIN_QUEUE:-false}

log() {
  printf '%s %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"
}

die() {
  log "ERROR: $*"
  exit 1
}

resolve_container_by_compose_service() {
  local svc="$1"
  local cid
  cid=$(docker ps -q --filter "label=com.docker.compose.service=${svc}" | head -n 1 || true)
  if [[ -z "$cid" ]]; then
    die "could not find running docker container for compose service '${svc}'"
  fi
  echo "$cid"
}

POSTGRES_CID=$(resolve_container_by_compose_service postgres)
RABBIT_CID=""
if docker ps -q --filter "label=com.docker.compose.service=rabbitmq" | head -n 1 >/dev/null 2>&1; then
  RABBIT_CID=$(resolve_container_by_compose_service rabbitmq)
fi

psql_exec() {
  local sql="$1"
  docker exec \
    -e PGPASSWORD="$ORCH_DB_PASSWORD" \
    "$POSTGRES_CID" \
    psql -U "$ORCH_DB_USER" -d "$ORCH_DB" -v ON_ERROR_STOP=1 -c "$sql"
}

psql_query_at() {
  # Print unaligned tuples, suitable for scripting.
  local sql="$1"
  docker exec \
    -e PGPASSWORD="$ORCH_DB_PASSWORD" \
    "$POSTGRES_CID" \
    psql -U "$ORCH_DB_USER" -d "$ORCH_DB" -v ON_ERROR_STOP=1 -A -t -c "$sql"
}

log "mode.apply=$APPLY employer_id=$EMPLOYER_ID min_age_seconds=$MIN_AGE_SECONDS"

# Show candidates.
log "listing RUNNING payruns"
psql_exec "SELECT pay_run_id, pay_period_id, status, finalize_started_at, created_at, updated_at FROM pay_run WHERE employer_id='${EMPLOYER_ID}' AND status='RUNNING' ORDER BY created_at DESC;"

# Find pay runs to clean (RUNNING + old enough).
# finalize_started_at can be null (treat as old enough).
readarray -t payruns < <(psql_query_at "SELECT pay_run_id FROM pay_run WHERE employer_id='${EMPLOYER_ID}' AND status='RUNNING' AND (finalize_started_at IS NULL OR EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - finalize_started_at)) >= ${MIN_AGE_SECONDS}) ORDER BY created_at DESC;")

if [[ ${#payruns[@]} -eq 0 ]]; then
  log "no eligible RUNNING payruns to clean"
else
  log "eligible RUNNING payruns: ${#payruns[@]}"
  for pr in "${payruns[@]}"; do
    log "candidate payRunId=$pr"
    psql_exec "SELECT status, COUNT(*) AS n FROM pay_run_item WHERE employer_id='${EMPLOYER_ID}' AND pay_run_id='${pr}' GROUP BY status ORDER BY status;"
  done

  if [[ "$APPLY" != "true" ]]; then
    log "dry-run only (set APPLY=true to delete)"
  else
    for pr in "${payruns[@]}"; do
      log "deleting payRunId=$pr (RUNNING only)"

      if [[ "$DELETE_OUTBOX_RABBIT" == "true" ]]; then
        psql_exec "BEGIN; DELETE FROM outbox_event WHERE destination_type='RABBIT' AND payload_json LIKE '%${pr}%'; DELETE FROM pay_run_item WHERE employer_id='${EMPLOYER_ID}' AND pay_run_id='${pr}'; DELETE FROM pay_run WHERE employer_id='${EMPLOYER_ID}' AND pay_run_id='${pr}' AND status='RUNNING'; COMMIT;"
      else
        psql_exec "BEGIN; DELETE FROM pay_run_item WHERE employer_id='${EMPLOYER_ID}' AND pay_run_id='${pr}'; DELETE FROM pay_run WHERE employer_id='${EMPLOYER_ID}' AND pay_run_id='${pr}' AND status='RUNNING'; COMMIT;"
      fi
    done
  fi
fi

# Rabbit queue cleanup
if [[ "$PURGE_RABBIT" == "true" ]]; then
  if [[ -z "$RABBIT_CID" ]]; then
    log "rabbitmq container not found; skipping rabbit purge"
    exit 0
  fi

  if [[ "$APPLY" != "true" ]]; then
    log "dry-run: would purge rabbit queues (set APPLY=true to purge)"
    docker exec "$RABBIT_CID" rabbitmqctl list_queues name messages consumers --quiet
    exit 0
  fi

  # Determine queues to purge based on flags.
  # We intentionally default to retry queues + DLQ only.
  queues_to_purge=()

  if [[ "$PURGE_RETRY_QUEUES" == "true" ]]; then
    while IFS=$'\t' read -r q _msgs _cons; do
      [[ -z "$q" || "$q" == "name" ]] && continue
      if [[ "$q" == payrun-finalize-employee-retry-* ]]; then
        queues_to_purge+=("$q")
      fi
    done < <(docker exec "$RABBIT_CID" rabbitmqctl list_queues name messages consumers --quiet)
  fi

  if [[ "$PURGE_DLQ" == "true" ]]; then
    queues_to_purge+=("payrun-finalize-employee-jobs-dlq")
  fi

  if [[ "$PURGE_MAIN_QUEUE" == "true" ]]; then
    queues_to_purge+=("payrun-finalize-employee-jobs")
  fi

  # De-dup
  if [[ ${#queues_to_purge[@]} -gt 0 ]]; then
    mapfile -t uniq_queues < <(printf '%s\n' "${queues_to_purge[@]}" | awk '!seen[$0]++')
  else
    uniq_queues=()
  fi

  if [[ ${#uniq_queues[@]} -eq 0 ]]; then
    log "no rabbit queues selected to purge"
  else
    for q in "${uniq_queues[@]}"; do
      log "purging rabbit queue: $q"
      docker exec "$RABBIT_CID" rabbitmqctl purge_queue "$q" --quiet || true
    done
  fi

  log "rabbit queues after purge:"
  docker exec "$RABBIT_CID" rabbitmqctl list_queues name messages consumers --quiet
fi

log "done"