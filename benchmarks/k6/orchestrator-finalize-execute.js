import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

// Run example:
//   k6 run \
//     -e ORCH_URL=http://localhost:8086 \
//     -e INTERNAL_JWT='eyJ...' \
//     -e EMPLOYER_ID=emp-1 \
//     -e PAY_PERIOD_ID=2025-01-BW1 \
//     -e EMPLOYEE_IDS=ee-1,ee-2 \
//     benchmarks/k6/orchestrator-finalize-execute.js
//
// Note: orchestrator internal endpoints require an internal JWT:
//   Authorization: Bearer ${INTERNAL_JWT}

const finalizeE2eMs = new Trend('payrun_finalize_e2e_ms');

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-vus',
      vus: __ENV.VUS ? Number(__ENV.VUS) : 4,
      duration: __ENV.DURATION || '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    // This is end-to-end (includes multiple HTTP calls); tune after first run.
    payrun_finalize_e2e_ms: ['p(50)<2000', 'p(95)<10000'],
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

export default function () {
  const orchUrl = env('ORCH_URL', 'http://localhost:8086');
  const employerId = env('EMPLOYER_ID', 'emp-1');
  const payPeriodId = env('PAY_PERIOD_ID', '2025-01-BW1');
  const employeeIds = env('EMPLOYEE_IDS', 'ee-1').split(',').map((s) => s.trim()).filter((s) => s.length > 0);

  const internalJwt = env('INTERNAL_JWT', '');

  const batchSize = Number(env('BATCH_SIZE', '25'));
  const maxItems = Number(env('MAX_ITEMS', '200'));
  const maxMillis = Number(env('MAX_MILLIS', '2000'));
  const requeueStaleMillis = Number(env('REQUEUE_STALE_MILLIS', String(10 * 60 * 1000)));
  const maxExecuteIterations = Number(env('MAX_EXECUTE_ITERATIONS', '50'));

  const startedAt = nowMs();

  // 1) Start finalization (public endpoint)
  const startUrl = `${orchUrl}/employers/${employerId}/payruns/finalize`;
  const startBody = JSON.stringify({
    payPeriodId,
    employeeIds,
    idempotencyKey: randId('k6'),
  });

  const startRes = http.post(startUrl, startBody, {
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
    },
  });

  check(startRes, {
    'startFinalize status is 202': (r) => r.status === 202,
  });

  if (startRes.status !== 202) {
    // Avoid cascading failures if env isn't seeded correctly.
    return;
  }

  const startJson = startRes.json();
  const payRunId = startJson.payRunId;

  // 2) Execute slices until terminal or no more work
  const execHeaders = {
    'Accept': 'application/json',
  };
  if (internalJwt && internalJwt.length > 0) {
    execHeaders['Authorization'] = `Bearer ${internalJwt}`;
  }

  let iter = 0;
  let finalStatus = null;
  while (iter < maxExecuteIterations) {
    const execUrl = `${orchUrl}/employers/${employerId}/payruns/internal/${payRunId}/execute` +
      `?batchSize=${batchSize}&maxItems=${maxItems}&maxMillis=${maxMillis}` +
      `&requeueStaleMillis=${requeueStaleMillis}&leaseOwner=k6`;

    const execRes = http.post(execUrl, null, { headers: execHeaders });

    check(execRes, {
      'execute status is 200': (r) => r.status === 200,
    });

    if (execRes.status !== 200) {
      // Likely missing internal auth token.
      break;
    }

    const execJson = execRes.json();
    finalStatus = execJson.finalStatus;
    const moreWork = execJson.moreWork;

    if (finalStatus === 'FINALIZED' || finalStatus === 'PARTIALLY_FINALIZED' || finalStatus === 'FAILED') {
      break;
    }
    if (moreWork === false) {
      break;
    }

    iter += 1;
    // Small sleep to reduce tight-loop hammering.
    sleep(0.05);
  }

  // 3) Fetch status (public endpoint) to confirm terminal state
  const statusUrl = `${orchUrl}/employers/${employerId}/payruns/${payRunId}?failureLimit=25`;
  const statusRes = http.get(statusUrl, { headers: { 'Accept': 'application/json' } });

  check(statusRes, {
    'getStatus status is 200': (r) => r.status === 200,
  });

  const elapsed = nowMs() - startedAt;
  finalizeE2eMs.add(elapsed);
}
