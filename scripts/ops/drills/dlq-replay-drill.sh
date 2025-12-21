#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
DLQ replay drill (compose-first).

This drill exercises:
- Rabbit-based per-employee finalize jobs
- DLQ accumulation under a controlled failure
- Operator replay via worker internal endpoint
- Recovery by fixing the root cause + replaying DLQ messages

Default stack:
- docker-compose.yml
- docker-compose.override.yml (exposes service ports)
- docker-compose.bench-parallel.yml (Rabbit + queue-driven finalize enabled)
- docker-compose.ops-drills.yml (DLQ replayer + fast DLQ settings)

Env (optional):
  EMPLOYER_ID          (default: EMP-DRILL)
  PAY_PERIOD_ID        (default: 2025-01-BW1)
  START_DATE           (default: 2025-01-01)
  END_DATE             (default: 2025-01-14)
  CHECK_DATE           (default: 2025-01-15)
  EMPLOYEE_COUNT       (default: 5)
  EMPLOYEE_ID_PREFIX   (default: EE-DRILL-)
  EMPLOYEE_ID_PAD      (default: 6)

  ORCH_URL             (default: http://localhost:8085)
  WORKER_URL           (default: http://localhost:8088)

  # Rabbit management API (from docker-compose.bench-parallel.yml)
  RABBIT_API_URL       (default: http://localhost:15672)
  RABBIT_USERNAME      (default: guest)
  RABBIT_PASSWORD      (default: guest)

  # Root cause injection: misconfigure worker's internal JWT signing key for orchestrator.
  # Orchestrator verifier key remains stable in docker-compose.ops-drills.yml.
  DOWNSTREAMS_ORCHESTRATOR_INTERNAL_JWT_SECRET_BAD (default: bad-internal-token)
  DOWNSTREAMS_ORCHESTRATOR_INTERNAL_JWT_SECRET_GOOD (default: dev-internal-token)

  # Worker internal auth (to call /internal/** endpoints)
  WORKER_INTERNAL_AUTH_JWT_KEYS_K1 (default: dev-worker-internal-token)

  OUT_DIR             (default: /tmp/us-payroll-ops-drills/<ts>/dlq-replay)
  DRY_RUN             (default: false)

Notes:
- This drill seeds benchmark data using benchmarks/seed/seed-benchmark-data.sh.
- The drill keeps employee counts small by default to avoid long runs.
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
require_cmd curl
require_cmd python3

EMPLOYER_ID=${EMPLOYER_ID:-EMP-DRILL}
PAY_PERIOD_ID=${PAY_PERIOD_ID:-2025-01-BW1}
START_DATE=${START_DATE:-2025-01-01}
END_DATE=${END_DATE:-2025-01-14}
CHECK_DATE=${CHECK_DATE:-2025-01-15}
EMPLOYEE_COUNT=${EMPLOYEE_COUNT:-5}
EMPLOYEE_ID_PREFIX=${EMPLOYEE_ID_PREFIX:-EE-DRILL-}
EMPLOYEE_ID_PAD=${EMPLOYEE_ID_PAD:-6}

ORCH_URL=${ORCH_URL:-http://localhost:8085}
WORKER_URL=${WORKER_URL:-http://localhost:8088}

RABBIT_API_URL=${RABBIT_API_URL:-http://localhost:15672}
RABBIT_USERNAME=${RABBIT_USERNAME:-guest}
RABBIT_PASSWORD=${RABBIT_PASSWORD:-guest}

DOWNSTREAMS_ORCHESTRATOR_INTERNAL_JWT_SECRET_BAD=${DOWNSTREAMS_ORCHESTRATOR_INTERNAL_JWT_SECRET_BAD:-bad-internal-token}
DOWNSTREAMS_ORCHESTRATOR_INTERNAL_JWT_SECRET_GOOD=${DOWNSTREAMS_ORCHESTRATOR_INTERNAL_JWT_SECRET_GOOD:-dev-internal-token}

WORKER_INTERNAL_AUTH_JWT_KEYS_K1=${WORKER_INTERNAL_AUTH_JWT_KEYS_K1:-dev-worker-internal-token}

DRY_RUN=${DRY_RUN:-false}

ts=$(date -u +%Y%m%dT%H%M%SZ)
OUT_DIR=${OUT_DIR:-"/tmp/us-payroll-ops-drills/${ts}/dlq-replay"}
mkdir -p "$OUT_DIR"

report="$OUT_DIR/dlq-replay-drill.json"

log() {
  printf '%s %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"
}

docker_compose() {
  # Keep the compose file set explicit for reproducibility.
  docker compose \
    -f docker-compose.yml \
    -f docker-compose.override.yml \
    -f docker-compose.bench-parallel.yml \
    -f docker-compose.ops-drills.yml \
    "$@"
}

rabbit_queue_depth() {
  local q="$1"
  curl -fsS -u "${RABBIT_USERNAME}:${RABBIT_PASSWORD}" \
    "${RABBIT_API_URL}/api/queues/%2F/${q}" \
    | python3 - <<'PY'
import json,sys
j=json.load(sys.stdin)
print(j.get('messages', 0))
PY
}

wait_http_ok() {
  local url="$1"
  local name="$2"
  for i in $(seq 1 60); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      log "ok: $name"
      return 0
    fi
    sleep 2
  done
  log "ERROR: timed out waiting for $name ($url)"
  return 1
}

write_report() {
  local phase="$1"
  shift
  python3 - "$report" "$phase" "$@" <<'PY'
import json,sys,datetime
path=sys.argv[1]
phase=sys.argv[2]
kv=sys.argv[3:]

obj={
  "drill": "dlq-replay",
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

# -----------------------------
# 1) Bring up stack (with injected failure)
# -----------------------------
export DOWNSTREAMS_ORCHESTRATOR_INTERNAL_JWT_SECRET="$DOWNSTREAMS_ORCHESTRATOR_INTERNAL_JWT_SECRET_BAD"
export WORKER_INTERNAL_AUTH_JWT_KEYS_K1

write_report "start" \
  "employerId=$EMPLOYER_ID" \
  "payPeriodId=$PAY_PERIOD_ID" \
  "employeeCount=$EMPLOYEE_COUNT" \
  "orchUrl=$ORCH_URL" \
  "workerUrl=$WORKER_URL" \
  "dlqQueue=payrun-finalize-employee-jobs-dlq" \
  "failureMode=worker_internal_jwt_mismatch"

if [[ "$DRY_RUN" == "true" ]]; then
  log "DRY_RUN=true: would run docker compose up"
  write_report "stack_up" "status=skipped"
else
  log "bringing up stack (worker uses bad internal JWT secret to force DLQ)"
  docker_compose up -d --build

  wait_http_ok "${ORCH_URL}/actuator/health" "orchestrator health"
  wait_http_ok "${WORKER_URL}/actuator/health" "worker health"
  wait_http_ok "${RABBIT_API_URL}/api/overview" "rabbit management api"

  write_report "stack_up" "status=ok"
fi

# -----------------------------
# 2) Seed minimal benchmark data
# -----------------------------
log "seeding benchmark data"
if [[ "$DRY_RUN" == "true" ]]; then
  log "DRY_RUN=true: would run benchmarks/seed/seed-benchmark-data.sh"
else
  EMPLOYER_ID="$EMPLOYER_ID" \
  PAY_PERIOD_ID="$PAY_PERIOD_ID" \
  START_DATE="$START_DATE" \
  END_DATE="$END_DATE" \
  CHECK_DATE="$CHECK_DATE" \
  EMPLOYEE_COUNT="$EMPLOYEE_COUNT" \
  EMPLOYEE_ID_PREFIX="$EMPLOYEE_ID_PREFIX" \
  SEED_TIME="false" \
  ./benchmarks/seed/seed-benchmark-data.sh
fi

write_report "seed" "status=ok"

# -----------------------------
# 3) Start a queue-driven payrun finalize
# -----------------------------
employee_ids_json=$(python3 - <<PY
import json
prefix='${EMPLOYEE_ID_PREFIX}'
pad=${EMPLOYEE_ID_PAD}
count=int('${EMPLOYEE_COUNT}')
ids=[f"{prefix}{str(i+1).zfill(pad)}" for i in range(count)]
print(json.dumps(ids))
PY
)

run_sequence=$(date +%s)
start_payload="$OUT_DIR/startFinalize.json"
cat >"$start_payload" <<EOF
{"payPeriodId":"${PAY_PERIOD_ID}","employeeIds":${employee_ids_json},"runSequence":${run_sequence},"idempotencyKey":"dlq-drill-${ts}"}
EOF

start_headers="$OUT_DIR/startFinalize.headers.txt"
start_body="$OUT_DIR/startFinalize.body.json"

log "starting payrun finalize (orchestrator)"
if [[ "$DRY_RUN" == "true" ]]; then
  log "DRY_RUN=true: would POST ${ORCH_URL}/employers/${EMPLOYER_ID}/payruns/finalize"
  pay_run_id="DRY_RUN"
else
  http_code=$(curl -sS -o "$start_body" -D "$start_headers" -w '%{http_code}' \
    -H 'Content-Type: application/json' \
    -X POST "${ORCH_URL}/employers/${EMPLOYER_ID}/payruns/finalize" \
    --data-binary "@${start_payload}")

  if [[ "$http_code" != "202" ]]; then
    log "ERROR: startFinalize expected 202, got $http_code"
    write_report "startFinalize" "status=error" "httpCode=$http_code"
    exit 1
  fi

  pay_run_id=$(python3 - <<PY
import json
with open('${start_body}','r',encoding='utf-8') as f:
  j=json.load(f)
print(j.get('payRunId',''))
PY
  )

  if [[ -z "$pay_run_id" ]]; then
    log "ERROR: could not parse payRunId from response"
    write_report "startFinalize" "status=error" "httpCode=$http_code"
    exit 1
  fi
fi

write_report "startFinalize" "status=ok" "payRunId=$pay_run_id" "runSequence=$run_sequence"

# -----------------------------
# 4) Wait for DLQ to grow
# -----------------------------
log "waiting for DLQ growth"
if [[ "$DRY_RUN" == "true" ]]; then
  log "DRY_RUN=true: would poll Rabbit DLQ depth"
else
  for _ in $(seq 1 60); do
    dlq=$(rabbit_queue_depth "payrun-finalize-employee-jobs-dlq")
    if [[ "$dlq" -gt 0 ]]; then
      write_report "dlq" "depth=$dlq" "status=grown"
      break
    fi
    sleep 2
  done
fi

# -----------------------------
# 5) Fix root cause (correct worker signing secret)
# -----------------------------
log "fixing root cause: restart worker with correct orchestrator internal JWT secret"
export DOWNSTREAMS_ORCHESTRATOR_INTERNAL_JWT_SECRET="$DOWNSTREAMS_ORCHESTRATOR_INTERNAL_JWT_SECRET_GOOD"

if [[ "$DRY_RUN" == "true" ]]; then
  log "DRY_RUN=true: would force-recreate payroll-worker-service"
  write_report "root_cause_fix" "status=skipped"
else
  docker_compose up -d --force-recreate payroll-worker-service

  wait_http_ok "${WORKER_URL}/actuator/health" "worker health (post-fix)"
  write_report "root_cause_fix" "status=ok"
fi

# -----------------------------
# 6) Replay DLQ
# -----------------------------
log "replaying DLQ"
if [[ "$DRY_RUN" == "true" ]]; then
  log "DRY_RUN=true: would call worker dlq replay endpoint"
else
  internal_jwt=$(EDGE_AUTH_HS256_SECRET="$WORKER_INTERNAL_AUTH_JWT_KEYS_K1" \
    ./scripts/mint-dev-jwt.sh --sub dlq-drill --iss us-payroll-platform --aud payroll-worker-service --minutes 5)

  replay_body="$OUT_DIR/dlqReplay.body.json"
  replay_code=$(curl -sS -o "$replay_body" -w '%{http_code}' \
    -H "Authorization: Bearer ${internal_jwt}" \
    -X POST "${WORKER_URL}/internal/jobs/finalize-employee/dlq/replay?maxMessages=10000&resetAttempt=true")

  if [[ "$replay_code" != "200" ]]; then
    log "ERROR: dlq replay expected 200, got $replay_code"
    write_report "dlq_replay" "status=error" "httpCode=$replay_code"
    exit 1
  fi

  replayed=$(python3 - <<PY
import json
with open('${replay_body}','r',encoding='utf-8') as f:
  j=json.load(f)
print(j.get('replayed',0))
PY
  )

  write_report "dlq_replay" "status=ok" "replayed=$replayed"
fi

# -----------------------------
# 7) Observe recovery
# -----------------------------
if [[ "$DRY_RUN" == "true" ]]; then
  log "DRY_RUN=true: skipping recovery observation"
else
  for _ in $(seq 1 60); do
    dlq=$(rabbit_queue_depth "payrun-finalize-employee-jobs-dlq")
    if [[ "$dlq" -eq 0 ]]; then
      write_report "recovery" "dlqDepth=0" "status=drained"
      break
    fi
    sleep 2
  done

  status_body="$OUT_DIR/payrunStatus.body.json"
  status_code=$(curl -sS -o "$status_body" -w '%{http_code}' \
    -H 'Accept: application/json' \
    "${ORCH_URL}/employers/${EMPLOYER_ID}/payruns/${pay_run_id}?failureLimit=25")

  write_report "payrun_status" "httpCode=$status_code"
fi

log "done. report=$report"
