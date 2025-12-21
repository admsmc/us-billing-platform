#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Single-tenant restore validation drill (compose-first).

This is a dev/staging drill artifact that demonstrates restore mechanics and invariant checks.
In production, restores are typically snapshots/PITR via your platform; this script is a repeatable
way to validate that restores are *possible* and that basic invariants hold.

Env (optional):
  SOURCE_DB      (default: us_payroll_orchestrator)
  RESTORE_DB     (default: <SOURCE_DB>_restore_<timestamp>)

  COMPOSE_FILE   (default: docker-compose.yml)
  OUT_DIR        (default: /tmp/us-payroll-ops-drills/<ts>/restore)
  DRY_RUN        (default: false)

Requires:
- docker + docker compose
- a running postgres service in docker compose

EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

require_cmd docker
require_cmd python3

COMPOSE_FILE=${COMPOSE_FILE:-docker-compose.yml}
SOURCE_DB=${SOURCE_DB:-us_payroll_orchestrator}

DRY_RUN=${DRY_RUN:-false}

ts=$(date -u +%Y%m%dT%H%M%SZ)
OUT_DIR=${OUT_DIR:-"/tmp/us-payroll-ops-drills/${ts}/restore"}
mkdir -p "$OUT_DIR"

RESTORE_DB=${RESTORE_DB:-"${SOURCE_DB}_restore_${ts}"}

report="$OUT_DIR/restore-drill.json"

log() {
  printf '%s %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"
}

docker_compose() {
  docker compose -f "$COMPOSE_FILE" "$@"
}

exec_psql() {
  local db="$1"
  local sql="$2"
  docker_compose exec -T postgres psql -U postgres -d "$db" -v ON_ERROR_STOP=1 -c "$sql"
}

query_scalar() {
  local db="$1"
  local sql="$2"
  docker_compose exec -T postgres psql -U postgres -d "$db" -t -A -c "$sql" | tr -d '[:space:]'
}

write_report() {
  local phase="$1"; shift
  python3 - "$report" "$phase" "$@" <<'PY'
import json,sys,datetime
path=sys.argv[1]
phase=sys.argv[2]
kv=sys.argv[3:]

obj={
  "drill": "single-tenant-restore",
  "updatedAt": datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
}
try:
  with open(path,'r',encoding='utf-8') as f:
    obj=json.load(f)
except FileNotFoundError:
  pass

obj.setdefault("phases",[])
entry={"phase": phase, "timestamp": obj["updatedAt"], "data": {}}
for item in kv:
  if '=' not in item:
    continue
  k,v=item.split('=',1)
  entry["data"][k]=v
obj["phases"].append(entry)

with open(path,'w',encoding='utf-8') as f:
  json.dump(obj,f,indent=2,sort_keys=True)
PY
}

write_report "start" "sourceDb=$SOURCE_DB" "restoreDb=$RESTORE_DB" "composeFile=$COMPOSE_FILE"

if [[ "$DRY_RUN" == "true" ]]; then
  log "DRY_RUN=true: would create/restore database and run validation queries"
  log "report=$report"
  exit 0
fi

# Ensure postgres is running.
docker_compose ps postgres >/dev/null

log "dumping source db (inside postgres container)"
dump_path="/tmp/${SOURCE_DB}-${ts}.dump"

docker_compose exec -T postgres bash -lc "pg_dump -U postgres --format=custom --no-owner --no-privileges -f '$dump_path' '$SOURCE_DB'"
write_report "backup" "dumpPath=$dump_path" "status=ok"

log "creating restore database (idempotent)"
exec_psql postgres "SELECT 'CREATE DATABASE ${RESTORE_DB}' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '${RESTORE_DB}')\\gexec"
write_report "create_db" "status=ok"

log "restoring into ${RESTORE_DB}"
docker_compose exec -T postgres bash -lc "pg_restore -U postgres --clean --if-exists --no-owner --no-privileges --dbname '${RESTORE_DB}' '${dump_path}'"
write_report "restore" "status=ok"

# Basic invariants: schema version and a few table counts (best-effort; tables may not exist in every DB).
latest_flyway=$(query_scalar "$RESTORE_DB" "SELECT COALESCE(MAX(installed_rank),0) FROM flyway_schema_history;" || true)
write_report "validate" "flywayInstalledRankMax=$latest_flyway"

# Orchestrator-known tables (safe to query; if missing, capture as 'n/a').
count_or_na() {
  local db="$1"
  local table="$2"
  local out
  out=$(docker_compose exec -T postgres psql -U postgres -d "$db" -t -A -c "SELECT COUNT(*) FROM ${table};" 2>/dev/null | tr -d '[:space:]' || true)
  if [[ -z "$out" ]]; then
    echo "n/a"
  else
    echo "$out"
  fi
}

pay_run_count=$(count_or_na "$RESTORE_DB" "pay_run")
pay_run_item_count=$(count_or_na "$RESTORE_DB" "pay_run_item")
paycheck_count=$(count_or_na "$RESTORE_DB" "paycheck")

write_report "validate_counts" "pay_run=$pay_run_count" "pay_run_item=$pay_run_item_count" "paycheck=$paycheck_count"

log "done. report=$report"