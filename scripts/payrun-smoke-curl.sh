#!/usr/bin/env bash
set -euo pipefail

# Crash-safe curl smoke test for orchestrator payrun flows.
#
# Design goals:
# - Never stream large JSON to the terminal (all bodies go to files).
# - Print only small, human-readable summaries (HTTP status, bytes, key ids).
# - Work with both:
#   - direct orchestrator access (default ORCH_URL=http://localhost:8085)
#   - internal endpoints (execute/reconcile/complete) when ORCH_INTERNAL_JWT (or ORCH_INTERNAL_JWT_SECRET) is provided.
#
# Usage examples:
#   ORCH_URL=http://localhost:8085 \
#   EMPLOYER_ID=emp-1 \
#   PAY_PERIOD_ID=pp-1 \
#   EMPLOYEE_IDS=e-1,e-2 \
#   ./scripts/payrun-smoke-curl.sh
#
#   # Exercise idempotency behavior:
#   IDEMPOTENCY_KEY=idem-1 REQUESTED_PAY_RUN_ID=run-idem-1 TEST_IDEMPOTENCY=true \
#   ./scripts/payrun-smoke-curl.sh
#
#   # If orchestrator internal auth is enabled (internal JWT):
#   ORCH_INTERNAL_JWT_SECRET=dev-internal-token \
#   ORCH_INTERNAL_JWT_KID=k1 \
#   EXECUTE_LOOP=true \
#   ./scripts/payrun-smoke-curl.sh

# ---- Config ----
ORCH_URL=${ORCH_URL:-http://localhost:8085}
EMPLOYER_ID=${EMPLOYER_ID:-emp-1}
PAY_PERIOD_ID=${PAY_PERIOD_ID:-pp-1}

# Comma-separated list. If empty, defaults to e-1.
EMPLOYEE_IDS=${EMPLOYEE_IDS:-e-1}

# Optional: request a specific payRunId and/or provide an idempotency key.
REQUESTED_PAY_RUN_ID=${REQUESTED_PAY_RUN_ID:-}
IDEMPOTENCY_KEY=${IDEMPOTENCY_KEY:-}

# Optional: pay run type/sequence (useful for multiple runs on the same pay period).
# Valid run types are controlled by orchestrator enum (e.g. REGULAR, OFF_CYCLE, CORRECTION, ADJUSTMENT).
RUN_TYPE=${RUN_TYPE:-}
RUN_SEQUENCE=${RUN_SEQUENCE:-}

# Optional: repeat requests to validate idempotency.
TEST_IDEMPOTENCY=${TEST_IDEMPOTENCY:-false}

# Poll payrun status until terminal.
STATUS_POLL=${STATUS_POLL:-true}
STATUS_POLL_INTERVAL_SECS=${STATUS_POLL_INTERVAL_SECS:-1}
STATUS_POLL_MAX_SECS=${STATUS_POLL_MAX_SECS:-300}
STATUS_FAILURE_LIMIT=${STATUS_FAILURE_LIMIT:-25}

# Optional: run internal execute loop (bench/dev-only; requires execute controller enabled + internal auth if configured).
EXECUTE_LOOP=${EXECUTE_LOOP:-false}
EXECUTE_BATCH_SIZE=${EXECUTE_BATCH_SIZE:-25}
EXECUTE_MAX_ITEMS=${EXECUTE_MAX_ITEMS:-200}
EXECUTE_MAX_MILLIS=${EXECUTE_MAX_MILLIS:-2000}
EXECUTE_REQUEUE_STALE_MILLIS=${EXECUTE_REQUEUE_STALE_MILLIS:-600000}
EXECUTE_LEASE_OWNER=${EXECUTE_LEASE_OWNER:-curl-smoke}
EXECUTE_PARALLELISM=${EXECUTE_PARALLELISM:-4}
EXECUTE_POLL_SLEEP_SECS=${EXECUTE_POLL_SLEEP_SECS:-1}
EXECUTE_MAX_LOOPS=${EXECUTE_MAX_LOOPS:-120}

# Internal auth (internal JWT). Leave blank if internal auth disabled.
# Provide either:
# - ORCH_INTERNAL_JWT (pre-minted), or
# - ORCH_INTERNAL_JWT_SECRET (+ optional claim/kid env vars) to mint a short-lived token.
ORCH_INTERNAL_JWT=${ORCH_INTERNAL_JWT:-}
ORCH_INTERNAL_JWT_SECRET=${ORCH_INTERNAL_JWT_SECRET:-}
ORCH_INTERNAL_JWT_KID=${ORCH_INTERNAL_JWT_KID:-k1}
ORCH_INTERNAL_JWT_ISSUER=${ORCH_INTERNAL_JWT_ISSUER:-us-payroll-platform}
ORCH_INTERNAL_JWT_AUDIENCE=${ORCH_INTERNAL_JWT_AUDIENCE:-payroll-orchestrator-service}
ORCH_INTERNAL_JWT_SUBJECT=${ORCH_INTERNAL_JWT_SUBJECT:-curl-smoke}
ORCH_INTERNAL_JWT_TTL_SECONDS=${ORCH_INTERNAL_JWT_TTL_SECONDS:-60}

# Where to write artifacts.
OUT_DIR=${OUT_DIR:-"/tmp/us-payroll-curl-smoke/$(date +%s)"}
mkdir -p "$OUT_DIR"

# ---- Helpers ----
log() {
  printf '%s %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"
}

die() {
  log "ERROR: $*"
  log "Artifacts: $OUT_DIR"
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "Missing required command: $1"
}

require_cmd curl
require_cmd python3

mint_orch_internal_jwt() {
  if [[ -n "${ORCH_INTERNAL_JWT}" ]]; then
    return
  fi
  if [[ -z "${ORCH_INTERNAL_JWT_SECRET}" ]]; then
    return
  fi

  ORCH_INTERNAL_JWT=$(python3 - <<'PY'
import os, time, json, uuid, hmac, hashlib, base64

def b64url(data: bytes) -> str:
  return base64.urlsafe_b64encode(data).rstrip(b'=').decode('ascii')

secret = os.environ.get('ORCH_INTERNAL_JWT_SECRET', '')
if not secret:
  raise SystemExit(2)

issuer = os.environ.get('ORCH_INTERNAL_JWT_ISSUER', 'us-payroll-platform')
audience = os.environ.get('ORCH_INTERNAL_JWT_AUDIENCE', 'payroll-orchestrator-service')
subject = os.environ.get('ORCH_INTERNAL_JWT_SUBJECT', 'curl-smoke')
kid = os.environ.get('ORCH_INTERNAL_JWT_KID', 'k1')
try:
  ttl = int(os.environ.get('ORCH_INTERNAL_JWT_TTL_SECONDS', '60'))
except Exception:
  ttl = 60

now = int(time.time())
header = {'alg': 'HS256', 'typ': 'JWT', 'kid': kid}
payload = {
  'iss': issuer,
  'sub': subject,
  'aud': audience,
  'iat': now,
  'exp': now + max(ttl, 1),
  'jti': str(uuid.uuid4()),
}

h64 = b64url(json.dumps(header, separators=(',', ':'), ensure_ascii=False).encode('utf-8'))
p64 = b64url(json.dumps(payload, separators=(',', ':'), ensure_ascii=False).encode('utf-8'))
msg = f"{h64}.{p64}".encode('ascii')
sig = hmac.new(secret.encode('utf-8'), msg, hashlib.sha256).digest()
print(f"{h64}.{p64}.{b64url(sig)}")
PY
  )
}

# json_get FILE KEY (top-level key only; keeps this script dependency-free)
json_get() {
  local file="$1"
  local key="$2"

  python3 - "$file" "$key" <<'PY'
import json, sys
path = sys.argv[1]
key = sys.argv[2]
try:
  with open(path, 'r', encoding='utf-8') as f:
    j = json.load(f)
  v = j.get(key)
  if v is None:
    sys.exit(2)
  if isinstance(v, (dict, list)):
    print(json.dumps(v))
  else:
    print(v)
except SystemExit:
  raise
except Exception:
  sys.exit(2)
PY
}

# json_get_path FILE dotted.path (supports nested dicts + lists by numeric segment)
json_get_path() {
  local file="$1"
  local path_expr="$2"

  python3 - "$file" "$path_expr" <<'PY'
import json, sys

file_path = sys.argv[1]
expr = sys.argv[2]

try:
  with open(file_path, 'r', encoding='utf-8') as f:
    cur = json.load(f)

  for seg in [s for s in expr.split('.') if s != '']:
    if isinstance(cur, list):
      try:
        idx = int(seg)
      except Exception:
        sys.exit(2)
      if idx < 0 or idx >= len(cur):
        sys.exit(2)
      cur = cur[idx]
      continue

    if not isinstance(cur, dict):
      sys.exit(2)

    if seg not in cur:
      sys.exit(2)
    cur = cur[seg]

  if isinstance(cur, (dict, list)):
    print(json.dumps(cur))
  else:
    print(cur)
except SystemExit:
  raise
except Exception:
  sys.exit(2)
PY
}

# Writes response to files; prints only a short summary.
# Usage: http_json <name> <method> <path> [<json-body-file>]
http_json() {
  local name="$1"
  local method="$2"
  local path="$3"
  local body_file_in="${4:-}"

  local base="$OUT_DIR/${name}"
  local headers_file="${base}.headers.txt"
  local body_file_out="${base}.body.json"
  local meta_file="${base}.meta.txt"

  local url="${ORCH_URL}${path}"

  local -a args
  args+=(--silent --show-error --location)
  args+=(--connect-timeout 3 --max-time 30)
  args+=(--retry 2 --retry-delay 1 --retry-all-errors)
  args+=(--request "$method")
  args+=(--dump-header "$headers_file")
  args+=(--output "$body_file_out")
  args+=(--write-out "http_code=%{http_code} time_total=%{time_total} size_download=%{size_download}\n")
  args+=(--header "Content-Type: application/json")

  if [[ -n "$IDEMPOTENCY_KEY" ]]; then
    args+=(--header "Idempotency-Key: ${IDEMPOTENCY_KEY}")
  fi

  if [[ "$path" == *"/payruns/internal/"* || "$path" == *"/paychecks/internal/"* ]]; then
    mint_orch_internal_jwt
    if [[ -n "$ORCH_INTERNAL_JWT" ]]; then
      args+=(--header "Authorization: Bearer ${ORCH_INTERNAL_JWT}")
    fi
  fi

  if [[ -n "$body_file_in" ]]; then
    args+=(--data-binary "@${body_file_in}")
  fi

  # Never print body to terminal. Only meta line.
  set +e
  local out
  out=$(curl "${args[@]}" "$url" 2>"${base}.stderr.txt")
  local rc=$?
  set -e

  printf '%s' "$out" >"$meta_file"

  local http_code
  http_code=$(sed -nE 's/^http_code=([0-9]+).*/\1/p' "$meta_file" | tail -n 1)
  local bytes
  bytes=$(sed -nE 's/.*size_download=([0-9]+).*/\1/p' "$meta_file" | tail -n 1)

  # IMPORTANT: write request summaries to stderr so callers can safely parse stdout.
  log "${name}: ${method} ${path} -> http=${http_code:-unknown} bytes=${bytes:-unknown} rc=${rc}" >&2

  # Expose paths to caller via stdout (space-separated)
  echo "$http_code" "$headers_file" "$body_file_out" "$meta_file" "$rc"
}

mk_start_finalize_body() {
  local out="$1"

  python3 - "$out" <<PY
import json, sys
out = sys.argv[1]
employee_ids = [e.strip() for e in "${EMPLOYEE_IDS}".split(",") if e.strip()]
body = {
  "payPeriodId": "${PAY_PERIOD_ID}",
  "employeeIds": employee_ids,
}
if "${RUN_TYPE}":
  body["runType"] = "${RUN_TYPE}"
if "${RUN_SEQUENCE}":
  try:
    body["runSequence"] = int("${RUN_SEQUENCE}")
  except Exception:
    pass
if "${REQUESTED_PAY_RUN_ID}":
  body["requestedPayRunId"] = "${REQUESTED_PAY_RUN_ID}"
if "${IDEMPOTENCY_KEY}":
  body["idempotencyKey"] = "${IDEMPOTENCY_KEY}"
with open(out, "w", encoding="utf-8") as f:
  json.dump(body, f)
PY
}

terminal_status() {
  case "$1" in
    FINALIZED|PARTIALLY_FINALIZED|FAILED) return 0 ;;
    *) return 1 ;;
  esac
}

log "out_dir=$OUT_DIR"

# ---- 1) Start finalize ----
start_body="$OUT_DIR/start_finalize.request.json"
mk_start_finalize_body "$start_body"

read -r code _headers body _meta _rc < <(http_json "01_start_finalize" "POST" "/employers/${EMPLOYER_ID}/payruns/finalize" "$start_body")

if [[ "$code" != "202" ]]; then
  die "start finalize failed (expected 202). See $OUT_DIR/01_start_finalize.*"
fi

PAY_RUN_ID=$(json_get "$body" payRunId || true)
if [[ -z "$PAY_RUN_ID" ]]; then
  die "could not parse payRunId from start finalize response. See $OUT_DIR/01_start_finalize.body.json"
fi

created=$(json_get "$body" created || true)
status=$(json_get "$body" status || true)
log "payRunId=$PAY_RUN_ID created=${created:-?} status=${status:-?}"

# ---- 2) Optional: idempotency retry (start finalize) ----
if [[ "$TEST_IDEMPOTENCY" == "true" && -n "$IDEMPOTENCY_KEY" ]]; then
  # Retry with a different requested id to ensure the idempotency key wins.
  REQUESTED_PAY_RUN_ID_2="${REQUESTED_PAY_RUN_ID:-run-idem-override}-retry"
  retry_body="$OUT_DIR/start_finalize.retry.request.json"

  python3 - "$retry_body" <<PY
import json, sys
out = sys.argv[1]
employee_ids = [e.strip() for e in "${EMPLOYEE_IDS}".split(",") if e.strip()]
body = {
  "payPeriodId": "${PAY_PERIOD_ID}",
  "employeeIds": employee_ids,
  "requestedPayRunId": "${REQUESTED_PAY_RUN_ID_2}",
  "idempotencyKey": "${IDEMPOTENCY_KEY}",
}
if "${RUN_TYPE}":
  body["runType"] = "${RUN_TYPE}"
if "${RUN_SEQUENCE}":
  try:
    body["runSequence"] = int("${RUN_SEQUENCE}")
  except Exception:
    pass
with open(out, "w", encoding="utf-8") as f:
  json.dump(body, f)
PY

  read -r code2 _h2 body2 _m2 _rc2 < <(http_json "02_start_finalize_retry" "POST" "/employers/${EMPLOYER_ID}/payruns/finalize" "$retry_body")
  if [[ "$code2" != "202" ]]; then
    die "idempotency retry start finalize failed (expected 202)."
  fi

  PAY_RUN_ID_2=$(json_get "$body2" payRunId || true)
  created2=$(json_get "$body2" created || true)

  log "retry payRunId=$PAY_RUN_ID_2 created=${created2:-?} (expected same payRunId, created=false)"
fi

# ---- 3) Optional: internal execute loop (bench/dev-only) ----
if [[ "$EXECUTE_LOOP" == "true" ]]; then
  if [[ -z "${ORCH_INTERNAL_JWT:-}" && -z "${ORCH_INTERNAL_JWT_SECRET:-}" ]]; then
    log "EXECUTE_LOOP=true but ORCH_INTERNAL_JWT/ORCH_INTERNAL_JWT_SECRET is empty; internal auth may fail if enabled."
  fi

  final_status=""
  for i in $(seq 1 "$EXECUTE_MAX_LOOPS"); do
    exec_path="/employers/${EMPLOYER_ID}/payruns/internal/${PAY_RUN_ID}/execute?batchSize=${EXECUTE_BATCH_SIZE}&maxItems=${EXECUTE_MAX_ITEMS}&maxMillis=${EXECUTE_MAX_MILLIS}&requeueStaleMillis=${EXECUTE_REQUEUE_STALE_MILLIS}&leaseOwner=${EXECUTE_LEASE_OWNER}&parallelism=${EXECUTE_PARALLELISM}"

    read -r exec_code _eh exec_body _em _erc < <(http_json "03_execute_${i}" "POST" "$exec_path")

    # 404 can mean the execute controller is disabled.
    if [[ "$exec_code" == "404" ]]; then
      log "execute endpoint returned 404 (likely orchestrator.payrun.execute.enabled=false); skipping execute loop"
      break
    fi

    if [[ "$exec_code" != "200" ]]; then
      log "execute call failed (http=${exec_code}); see $OUT_DIR/03_execute_${i}.*"
      break
    fi

    final_status=$(json_get "$exec_body" finalStatus || true)
    more_work=$(json_get "$exec_body" moreWork || true)
    processed=$(json_get "$exec_body" processed || true)

    log "execute[$i]: processed=${processed:-?} finalStatus=${final_status:-} moreWork=${more_work:-?}"

    if [[ -n "$final_status" && "$final_status" != "null" ]]; then
      # If finalStatus is set, the run should be terminal.
      break
    fi

    sleep "$EXECUTE_POLL_SLEEP_SECS"
  done
fi

# ---- 4) Poll payrun status (public) ----
status_path="/employers/${EMPLOYER_ID}/payruns/${PAY_RUN_ID}?failureLimit=${STATUS_FAILURE_LIMIT}"
status_log="$OUT_DIR/status.poll.log"

poll_started_epoch=$(date +%s)
final_status=""

if [[ "$STATUS_POLL" == "true" ]]; then
  log "polling status until terminal (max_secs=$STATUS_POLL_MAX_SECS interval_secs=$STATUS_POLL_INTERVAL_SECS)"

  while true; do
    read -r status_code _sh status_body _sm _src < <(http_json "04_get_status_poll" "GET" "$status_path")
    if [[ "$status_code" != "200" ]]; then
      die "get status failed (expected 200)."
    fi

    final_status=$(json_get "$status_body" status || true)
    total=$(json_get_path "$status_body" counts.total || true)
    queued=$(json_get_path "$status_body" counts.queued || true)
    running=$(json_get_path "$status_body" counts.running || true)
    succeeded=$(json_get_path "$status_body" counts.succeeded || true)
    failed=$(json_get_path "$status_body" counts.failed || true)
    e2e_ms=$(json_get "$status_body" finalizeE2eMs || true)

    log "status=${final_status:-?} counts total=${total:-?} queued=${queued:-?} running=${running:-?} ok=${succeeded:-?} failed=${failed:-?} e2eMs=${e2e_ms:-}" | tee -a "$status_log" >/dev/null

    if [[ -n "$final_status" ]] && terminal_status "$final_status"; then
      break
    fi

    now_epoch=$(date +%s)
    elapsed=$((now_epoch - poll_started_epoch))
    if [[ "$elapsed" -ge "$STATUS_POLL_MAX_SECS" ]]; then
      log "status poll timed out after ${elapsed}s; leaving run in status=${final_status:-unknown}"
      break
    fi

    sleep "$STATUS_POLL_INTERVAL_SECS"
  done
else
  read -r status_code _sh status_body _sm _src < <(http_json "04_get_status_once" "GET" "$status_path")
  if [[ "$status_code" != "200" ]]; then
    die "get status failed (expected 200)."
  fi
  final_status=$(json_get "$status_body" status || true)
  log "status=${final_status:-?}"
fi

# Snapshot final status payload.
read -r _final_code _fh _final_body _fm _frc < <(http_json "04_get_status_final" "GET" "$status_path")

# ---- 5) Approve (if terminal) ----
if [[ -n "$final_status" ]] && terminal_status "$final_status"; then
  read -r approve_code _ah approve_body _am _arc < <(http_json "05_approve" "POST" "/employers/${EMPLOYER_ID}/payruns/${PAY_RUN_ID}/approve")
  log "approve http=${approve_code}"
else
  log "skipping approve (status not terminal: ${final_status:-unknown})"
fi

# ---- 6) Initiate payments (optional; requires approved + domain rules) ----
# Enable by setting INITIATE_PAYMENTS=true (and optionally PAYMENTS_IDEMPOTENCY_KEY).
INITIATE_PAYMENTS=${INITIATE_PAYMENTS:-false}
PAYMENTS_IDEMPOTENCY_KEY=${PAYMENTS_IDEMPOTENCY_KEY:-}

if [[ "$INITIATE_PAYMENTS" == "true" ]]; then
  # Temporarily override IDEMPOTENCY_KEY header for this request only.
  old_idem="$IDEMPOTENCY_KEY"
  IDEMPOTENCY_KEY="$PAYMENTS_IDEMPOTENCY_KEY"

  read -r pay_code _ph pay_body _pm _prc < <(http_json "06_payments_initiate" "POST" "/employers/${EMPLOYER_ID}/payruns/${PAY_RUN_ID}/payments/initiate")
  log "payments/initiate http=${pay_code}"

  IDEMPOTENCY_KEY="$old_idem"
fi

log "done. Artifacts: $OUT_DIR"