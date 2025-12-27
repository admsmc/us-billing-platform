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

// Diagnostics to reveal when results are dominated by client polling cadence.
const statusPolls = new Trend('payrun_finalize_rabbit_status_polls');
const pollSleepMs = new Trend('payrun_finalize_rabbit_poll_sleep_ms');
// Ratio of time spent sleeping between polls vs total client-observed e2e time.
// Values near 1.0 indicate the benchmark is dominated by polling cadence rather than actual work.
const pollQuantizationRatio = new Trend('payrun_finalize_rabbit_poll_quantization_ratio');

// Server-derived finalize timing (requires orchestrator status endpoint to return finalizeE2eMs).
const serverE2eMs = new Trend('payrun_finalize_rabbit_server_e2e_ms');
const serverE2eMsPerEmployee = new Trend('payrun_finalize_rabbit_server_e2e_ms_per_employee');
const serverEmployeesPerSec = new Trend('payrun_finalize_rabbit_server_employees_per_sec');

// Optional Phase B: render statement JSON and/or generate CSV from DB-stored paychecks.
const renderMs = new Trend('payrun_finalize_rabbit_render_ms');
const renderStatementsBytes = new Trend('payrun_finalize_rabbit_render_statement_bytes_total');
const renderCsvBytes = new Trend('payrun_finalize_rabbit_render_csv_bytes_total');

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
    // In local/dev environments we only require that the metric is defined;
    // callers can tighten this via K6_THRESHOLD_EMPLOYEES_PER_SEC if desired.
    payrun_finalize_rabbit_employees_per_sec: [__ENV.K6_THRESHOLD_EMPLOYEES_PER_SEC || 'p(50)>=0'],
  },
};

function env(name, def) {
  return __ENV[name] && __ENV[name].length > 0 ? __ENV[name] : def;
}

function truthy(v) {
  if (!v) return false;
  return String(v).toLowerCase() === 'true' || String(v) === '1';
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

  // IMPORTANT: pay_run has a unique constraint on (employer_id, pay_period_id, run_type, run_sequence).
  // If run_sequence is constant, repeated benchmark runs can reuse an old payrun, which makes server-side
  // finalize timing meaningless. Make runSequence unique by default.
  const runSequenceEnv = env('RUN_SEQUENCE', '');
  const runSequence = runSequenceEnv && runSequenceEnv.length > 0
    ? Number(runSequenceEnv)
    : (Math.floor(Date.now() / 1000) + __ITER);

  const employeeIds = buildEmployeeIds();
  const employeeCount = employeeIds.length;

  const pollIntervalMs = Number(env('POLL_INTERVAL_MS', '250'));
  const pollStrategy = env('POLL_STRATEGY', 'fixed'); // fixed|adaptive
  const maxWaitSeconds = Number(env('MAX_WAIT_SECONDS', '1800')); // 30m default

  // Phase B (optional): benchmark rendering/export from DB-stored paychecks for this payrun.
  const renderAfterFinalize = truthy(env('RENDER_AFTER_FINALIZE', 'false'));
  const benchHeader = env('BENCH_HEADER', 'X-Benchmark-Token');
  const benchToken = env('BENCH_TOKEN', '');
  const renderSerializeJson = truthy(env('RENDER_SERIALIZE_JSON', 'true'));
  const renderGenerateCsv = truthy(env('RENDER_GENERATE_CSV', 'false'));
  const renderLimit = env('RENDER_LIMIT', '');

  const startedAt = nowMs();

  // 1) Start finalization
  const startUrl = `${orchUrl}/employers/${employerId}/payruns/finalize`;

  // IMPORTANT: don't send requestedPayRunId unless explicitly provided.
  // An empty string will be treated as a real payRunId by the orchestrator,
  // which can lead to duplicate key violations (pay_run_id="").
  const requestedPayRunId = env('REQUESTED_PAYRUN_ID', '');

  const startPayload = {
    payPeriodId,
    // Keep run type default (REGULAR), but vary sequence so each run creates a new payrun.
    runSequence,
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
  // Emit payRunId so the bench harness can export per-run paycheck artifacts.
  console.log(`PAYRUN_ID=${payRunId}`);

  // 2) Poll until terminal
  const deadlineMs = startedAt + maxWaitSeconds * 1000;
  let finalStatus = null;

  let polls = 0;
  let totalSleepMs = 0;
  let observedServerE2eMs = null;

  function effectivePollIntervalMs(pollsSoFar) {
    if (pollStrategy !== 'adaptive') return pollIntervalMs;

    // Adaptive strategy: reduce quantization for fast runs while still avoiding a tight loop.
    // - first ~0.5s: 25ms
    // - next ~2s: 100ms
    // - then: configured pollIntervalMs
    if (pollsSoFar < 20) return Math.min(25, pollIntervalMs);
    if (pollsSoFar < 40) return Math.min(100, pollIntervalMs);
    return pollIntervalMs;
  }

  while (nowMs() < deadlineMs) {
    polls += 1;

    const statusUrl = `${orchUrl}/employers/${employerId}/payruns/${payRunId}?failureLimit=25`;
    const statusRes = http.get(statusUrl, { headers: { 'Accept': 'application/json' } });

    check(statusRes, {
      'getStatus status is 200': (r) => r.status === 200,
    });

    if (statusRes.status === 200) {
      const statusJson = statusRes.json();
      finalStatus = statusJson.status;

      // If orchestrator provides server-side finalize timing, capture it.
      // This avoids client polling artifacts when comparing worker replica counts.
      if (statusJson.finalizeE2eMs !== undefined && statusJson.finalizeE2eMs !== null) {
        observedServerE2eMs = Number(statusJson.finalizeE2eMs);
      }

      if (isTerminal(finalStatus)) break;
    }

    const sleepMs = effectivePollIntervalMs(polls);
    totalSleepMs += sleepMs;
    sleep(sleepMs / 1000.0);
  }

  const elapsed = nowMs() - startedAt;
  e2eMs.add(elapsed);
  if (employeeCount > 0) {
    e2eMsPerEmployee.add(elapsed / employeeCount);
    employeesPerSec.add(employeeCount / (elapsed / 1000.0));
  }

  // Poll diagnostics
  statusPolls.add(polls);
  pollSleepMs.add(totalSleepMs);
  if (elapsed > 0) {
    pollQuantizationRatio.add(totalSleepMs / elapsed);
  }

  // Server-side finalize timing (if available)
  if (observedServerE2eMs !== null && employeeCount > 0) {
    serverE2eMs.add(observedServerE2eMs);
    serverE2eMsPerEmployee.add(observedServerE2eMs / employeeCount);
    serverEmployeesPerSec.add(employeeCount / (observedServerE2eMs / 1000.0));
  }

  check(null, {
    'payrun reached terminal status before timeout': () => isTerminal(finalStatus),
  });

  // 3) Phase B: render/export artifacts from DB-stored paychecks (optional)
  if (renderAfterFinalize && isTerminal(finalStatus)) {
    const renderUrl = `${orchUrl}/benchmarks/employers/${employerId}/payruns/${payRunId}/render-pay-statements`;

    const renderPayload = {
      serializeJson: renderSerializeJson,
      generateCsv: renderGenerateCsv,
    };

    if (renderLimit && String(renderLimit).length > 0) {
      renderPayload.limit = Number(renderLimit);
    }

    const renderStartedAt = nowMs();
    const renderRes = http.post(renderUrl, JSON.stringify(renderPayload), {
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
        [benchHeader]: benchToken,
      },
    });

    check(renderRes, {
      'render status is 200': (r) => r.status === 200,
    });

    if (renderRes.status === 200) {
      renderMs.add(nowMs() - renderStartedAt);
      const body = renderRes.json();
      if (body && body.serializedBytesTotal !== undefined) {
        renderStatementsBytes.add(Number(body.serializedBytesTotal));
      }
      if (body && body.csvBytesTotal !== undefined) {
        renderCsvBytes.add(Number(body.csvBytesTotal));
      }
    }
  }
}
