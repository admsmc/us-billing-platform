import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

// Queue-driven payrun finalize benchmark.
//
// This exercises the enterprise-style flow:
// 1) POST /employers/{employerId}/payruns/finalize
//    - orchestrator enqueues one job per employee into RabbitMQ via outbox
// 2) Worker replicas consume jobs and call orchestrator internal per-item endpoint
// 3) Orchestrator background finalizer transitions payrun to terminal
// 4) GET /employers/{employerId}/payruns/{payRunId} until terminal
//
// Run example:
//   k6 run \
//     -e ORCH_URL=http://localhost:8085 \
//     -e EMPLOYER_ID=EMP-BENCH \
//     -e PAY_PERIOD_ID=2025-01-BW1 \
//     -e EMPLOYEE_ID_PREFIX=EE-BENCH- \
//     -e EMPLOYEE_ID_START=1 \
//     -e EMPLOYEE_COUNT=1000 \
//     -e RUNS=1 \
//     benchmarks/k6/orchestrator-rabbit-finalize.js
//
// Notes:
// - This benchmark is intentionally low-concurrency at the k6 layer: it measures
//   payrun wall-clock completion time under varying worker replica counts.
// - It emits both latency and throughput Trends:
//   - payrun_finalize_rabbit_e2e_ms
//   - payrun_finalize_rabbit_employees_per_sec

const e2eMs = new Trend('payrun_finalize_rabbit_e2e_ms');
const e2eMsPerEmployee = new Trend('payrun_finalize_rabbit_e2e_ms_per_employee');
const employeesPerSec = new Trend('payrun_finalize_rabbit_employees_per_sec');

export const options = {
  scenarios: {
    once: {
      executor: 'per-vu-iterations',
      vus: __ENV.VUS ? Number(__ENV.VUS) : 1,
      iterations: __ENV.RUNS ? Number(__ENV.RUNS) : 1,
      maxDuration: __ENV.MAX_DURATION || '30m',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    // Defaults are intentionally loose; tune after you collect a baseline.
    payrun_finalize_rabbit_e2e_ms: ['p(50)<1800000'],
    // This is a throughput metric; keep thresholds loose by default.
    payrun_finalize_rabbit_employees_per_sec: ['p(50)>0'],
  },
};

function env(name, def) {
  return __ENV[name] && __ENV[name].length > 0 ? __ENV[name] : def;
}

function nowMs() {
  return new Date().getTime();
}

function randId(prefix) {
  // Not a UUID, but sufficient for idempotency keys in dev.
  return `${prefix}-${__VU}-${__ITER}-${Math.floor(Math.random() * 1e9)}`;
}

function padLeft(n, width) {
  const s = String(n);
  return s.length >= width ? s : '0'.repeat(width - s.length) + s;
}

function buildEmployeeIds() {
  const explicit = env('EMPLOYEE_IDS', '')
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0);

  if (explicit.length > 0) return explicit;

  const prefix = env('EMPLOYEE_ID_PREFIX', 'EE-BENCH-');
  const start = Number(env('EMPLOYEE_ID_START', '1'));
  const count = Number(env('EMPLOYEE_COUNT', '1'));
  const pad = Number(env('EMPLOYEE_ID_PAD', '6'));

  const ids = [];
  for (let i = 0; i < count; i += 1) {
    const n = start + i;
    ids.push(`${prefix}${padLeft(n, pad)}`);
  }
  return ids;
}

function isTerminal(status) {
  return status === 'FINALIZED' || status === 'PARTIALLY_FINALIZED' || status === 'FAILED';
}

export default function () {
  const orchUrl = env('ORCH_URL', 'http://localhost:8085');
  const employerId = env('EMPLOYER_ID', 'EMP-BENCH');
  const payPeriodId = env('PAY_PERIOD_ID', '2025-01-BW1');

  const employeeIds = buildEmployeeIds();
  const employeeCount = employeeIds.length;

  const pollIntervalMs = Number(env('POLL_INTERVAL_MS', '250'));
  const maxWaitSeconds = Number(env('MAX_WAIT_SECONDS', '1800')); // 30m default

  const startedAt = nowMs();

  // 1) Start finalization
  const startUrl = `${orchUrl}/employers/${employerId}/payruns/finalize`;

  // IMPORTANT: don't send requestedPayRunId unless explicitly provided.
  // An empty string will be treated as a real payRunId by the orchestrator,
  // which can lead to duplicate key violations (pay_run_id="").
  const requestedPayRunId = env('REQUESTED_PAYRUN_ID', '');

  const startPayload = {
    payPeriodId,
    employeeIds,
    idempotencyKey: randId('k6-rabbit'),
  };

  if (requestedPayRunId && requestedPayRunId.length > 0) {
    startPayload.requestedPayRunId = requestedPayRunId;
  }

  const startBody = JSON.stringify(startPayload);

  const startRes = http.post(startUrl, startBody, {
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
    },
  });

  check(startRes, {
    'startFinalize status is 202': (r) => r.status === 202,
  });

  if (startRes.status !== 202) return;

  const payRunId = startRes.json().payRunId;

  // 2) Poll until terminal
  const deadlineMs = startedAt + maxWaitSeconds * 1000;
  let finalStatus = null;

  while (nowMs() < deadlineMs) {
    const statusUrl = `${orchUrl}/employers/${employerId}/payruns/${payRunId}?failureLimit=25`;
    const statusRes = http.get(statusUrl, { headers: { 'Accept': 'application/json' } });

    check(statusRes, {
      'getStatus status is 200': (r) => r.status === 200,
    });

    if (statusRes.status === 200) {
      const statusJson = statusRes.json();
      finalStatus = statusJson.status;
      if (isTerminal(finalStatus)) break;
    }

    sleep(pollIntervalMs / 1000.0);
  }

  const elapsed = nowMs() - startedAt;
  e2eMs.add(elapsed);
  if (employeeCount > 0) {
    e2eMsPerEmployee.add(elapsed / employeeCount);
    employeesPerSec.add(employeeCount / (elapsed / 1000.0));
  }

  check(null, {
    'payrun reached terminal status before timeout': () => isTerminal(finalStatus),
  });
}
