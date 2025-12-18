import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

// Run example (range-based):
// NOTE: for realistic overtime, enable time-derived shaping and seed time entries:
//   - TIME_ENABLED=true (worker/orchestrator)
//   - SEED_TIME=true (when running ./benchmarks/seed/seed-benchmark-data.sh)
//
//   k6 run \
//     -e WORKER_URL=http://localhost:8088 \
//     -e BENCH_TOKEN=dev-secret \
//     -e EMPLOYER_ID=EMP-BENCH \
//     -e PAY_PERIOD_ID=2025-01-BW1 \
//     -e EMPLOYEE_ID_PREFIX=EE-BENCH- \
//     -e EMPLOYEE_ID_START=1 \
//     -e EMPLOYEE_ID_END=200 \
//     -e VERIFY_SEED=true \
//     benchmarks/k6/worker-hr-backed-pay-period.js
//
// Or explicit list:
//   k6 run -e EMPLOYEE_IDS=EE-BENCH-000003,EE-BENCH-000006 benchmarks/k6/worker-hr-backed-pay-period.js

const e2eMs = new Trend('worker_hr_backed_e2e_ms');
// Normalized metric so the same test is "fair" across different employee counts.
const e2eMsPerEmployee = new Trend('worker_hr_backed_e2e_ms_per_employee');

// Local bootRun + dockerized Postgres can be relatively slow; provide realistic defaults,
// but allow callers to override on the command line.
const e2eP50TargetMs = __ENV.E2E_P50_MS ? Number(__ENV.E2E_P50_MS) : 9000;
const e2eP95TargetMs = __ENV.E2E_P95_MS ? Number(__ENV.E2E_P95_MS) : 15000;

// "Fair" defaults: ms per employee processed.
const e2ePerEmployeeP50TargetMs = __ENV.E2E_PER_EMP_P50_MS ? Number(__ENV.E2E_PER_EMP_P50_MS) : 60;
const e2ePerEmployeeP95TargetMs = __ENV.E2E_PER_EMP_P95_MS ? Number(__ENV.E2E_PER_EMP_P95_MS) : 120;

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
    // Absolute end-to-end time (still useful when running a fixed N).
    worker_hr_backed_e2e_ms: [`p(50)<${e2eP50TargetMs}`, `p(95)<${e2eP95TargetMs}`],
    // Normalized end-to-end time per employee (fair across different N).
    worker_hr_backed_e2e_ms_per_employee: [`p(50)<${e2ePerEmployeeP50TargetMs}`, `p(95)<${e2ePerEmployeeP95TargetMs}`],
  },
};

function env(name, def) {
  return __ENV[name] && __ENV[name].length > 0 ? __ENV[name] : def;
}

function truthy(v) {
  if (!v) return false;
  return String(v).toLowerCase() === 'true' || String(v) === '1';
}

export function setup() {
  const workerUrl = env('WORKER_URL', 'http://localhost:8088');
  const employerId = env('EMPLOYER_ID', 'EMP-BENCH');
  const payPeriodId = env('PAY_PERIOD_ID', '2025-01-BW1');

  const headerName = env('BENCH_HEADER', 'X-Benchmark-Token');
  const token = env('BENCH_TOKEN', '');

  // Resolve employeeIdEnd via recommended-range endpoint if not provided.
  const endEnv = Number(env('EMPLOYEE_ID_END', '0'));
  const prefix = env('EMPLOYEE_ID_PREFIX', 'EE-BENCH-');
  const pad = Number(env('EMPLOYEE_ID_PAD', '6'));
  const start = Number(env('EMPLOYEE_ID_START', '1'));

  let discoveredEnd = endEnv;
  let discoveredCount = 0;

  const explicit = env('EMPLOYEE_IDS', '').split(',').map((s) => s.trim()).filter((s) => s.length > 0);

  if (!endEnv || endEnv <= 0) {
    const rangeUrl = `${workerUrl}/benchmarks/employers/${employerId}/employee-id-range?prefix=${encodeURIComponent(prefix)}&padWidth=${pad}&startInclusive=${start}`;
    const rangeRes = http.get(rangeUrl, {
      headers: {
        'Accept': 'application/json',
        [headerName]: token,
      },
    });

    check(rangeRes, {
      'employee range status is 200': (r) => r.status === 200,
    });

    if (rangeRes.status !== 200) {
      throw new Error(`employee-id-range failed: status=${rangeRes.status} body=${rangeRes.body}`);
    }

    const rangeJson = rangeRes.json();
    discoveredEnd = rangeJson.safeEndInclusive || 0;

    if (!discoveredEnd || discoveredEnd <= 0) {
      throw new Error(`employee-id-range did not return safeEndInclusive: ${rangeRes.body}`);
    }

    // If the worker reported a safeCount, use it; otherwise infer from start/end.
    discoveredCount = Number(rangeJson.safeCount || (discoveredEnd - start + 1));
  } else {
    // If caller provided explicit IDs, prefer that count. Otherwise infer from start/end.
    discoveredCount = explicit.length > 0 ? explicit.length : Math.max(0, discoveredEnd - start + 1);
  }

  const verify = truthy(__ENV.VERIFY_SEED);
  if (!verify) return { employeeIdEnd: discoveredEnd, employeeCount: discoveredCount };

  // Pick a single employee id to verify connectivity.
  const employeeId = explicit.length > 0
    ? explicit[0]
    : `${prefix}${String(start).padStart(pad, '0')}`;

  const verifyUrl = `${workerUrl}/benchmarks/employers/${employerId}/seed-verification?payPeriodId=${payPeriodId}&employeeId=${employeeId}`;
  const res = http.get(verifyUrl, {
    headers: {
      'Accept': 'application/json',
      [headerName]: token,
    },
  });

  check(res, {
    'seed verification status is 200': (r) => r.status === 200,
  });

  if (res.status !== 200) {
    throw new Error(`seed verification failed: status=${res.status} body=${res.body}`);
  }

  const json = res.json();
  if (!json.ok) {
    throw new Error(`seed verification returned ok=false: ${res.body}`);
  }

  return { employeeIdEnd: discoveredEnd, employeeCount: discoveredCount };
}

export default function (data) {
  const workerUrl = env('WORKER_URL', 'http://localhost:8088');
  const employerId = env('EMPLOYER_ID', 'EMP-BENCH');
  const payPeriodId = env('PAY_PERIOD_ID', '2025-01-BW1');

  const headerName = env('BENCH_HEADER', 'X-Benchmark-Token');
  const token = env('BENCH_TOKEN', '');

  const url = `${workerUrl}/benchmarks/employers/${employerId}/hr-backed-pay-period`;

  const explicitIds = env('EMPLOYEE_IDS', '').split(',').map((s) => s.trim()).filter((s) => s.length > 0);
  const prefix = env('EMPLOYEE_ID_PREFIX', 'EE-BENCH-');
  const start = Number(env('EMPLOYEE_ID_START', '1'));
  const end = Number(env('EMPLOYEE_ID_END', '0')) > 0
    ? Number(env('EMPLOYEE_ID_END', '0'))
    : (data && data.employeeIdEnd ? Number(data.employeeIdEnd) : 10);
  const pad = Number(env('EMPLOYEE_ID_PAD', '6'));

  const correctnessMode = env('CORRECTNESS_MODE', '');
  const expectedDigestXor = env('EXPECTED_DIGEST_XOR', '');
  const expectedNetTotalCents = env('EXPECTED_NET_TOTAL_CENTS', '');
  const expectedGrossTotalCents = env('EXPECTED_GROSS_TOTAL_CENTS', '');

  const wantCorrectness = (correctnessMode && correctnessMode.length > 0)
    || (expectedDigestXor && expectedDigestXor.length > 0)
    || (expectedNetTotalCents && expectedNetTotalCents.length > 0)
    || (expectedGrossTotalCents && expectedGrossTotalCents.length > 0);

  const payload = {
    payPeriodId,
  };

  if (wantCorrectness) {
    payload.correctnessMode = correctnessMode && correctnessMode.length > 0 ? correctnessMode : 'digest';
  }

  if (explicitIds.length > 0) {
    payload.employeeIds = explicitIds;
  } else {
    payload.employeeIdPrefix = prefix;
    payload.employeeIdStartInclusive = start;
    payload.employeeIdEndInclusive = end;
    payload.employeeIdPadWidth = pad;
  }

  const body = JSON.stringify(payload);

  const selectedCount = explicitIds.length > 0 ? explicitIds.length : Math.max(0, end - start + 1);

  const startedAt = new Date().getTime();

  const res = http.post(url, body, {
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
      [headerName]: token,
    },
  });

  check(res, {
    'status is 200': (r) => r.status === 200,
  });

  if (res.status === 200) {
    const elapsed = new Date().getTime() - startedAt;
    e2eMs.add(elapsed);

    // Prefer the employeeCount discovered in setup(), but fall back to the request-selected count.
    const count = data && data.employeeCount ? Number(data.employeeCount) : selectedCount;
    if (count && count > 0) {
      e2eMsPerEmployee.add(elapsed / count);
    }

    // Optional correctness check (digest + aggregates) without returning full paychecks.
    // Enable by providing any of:
    // - CORRECTNESS_MODE=digest
    // - EXPECTED_DIGEST_XOR=<number>
    // - EXPECTED_NET_TOTAL_CENTS=<number>
    // - EXPECTED_GROSS_TOTAL_CENTS=<number>
    const wantCorrectness = payload.correctnessMode && String(payload.correctnessMode).length > 0;
    if (wantCorrectness) {
      const json = res.json();
      const c = json && json.correctness ? json.correctness : null;

      check(c, {
        'correctness summary present': (x) => x !== null,
      });

      if (c) {
        // Basic invariant: Σ(gross - employeeTaxes - deductions - net) == 0
        check(c, {
          'net identity delta is zero': (x) => Number(x.netIdentityDeltaCentsTotal) === 0,
        });

        const expectedDigestXor = env('EXPECTED_DIGEST_XOR', '');
        if (expectedDigestXor && expectedDigestXor.length > 0) {
          check(c, {
            'digestXor matches expected': (x) => String(x.digestXor) === String(expectedDigestXor),
          });
        }

        const expectedNetTotalCents = env('EXPECTED_NET_TOTAL_CENTS', '');
        if (expectedNetTotalCents && expectedNetTotalCents.length > 0) {
          check(c, {
            'netCentsTotal matches expected': (x) => String(x.netCentsTotal) === String(expectedNetTotalCents),
          });
        }

        const expectedGrossTotalCents = env('EXPECTED_GROSS_TOTAL_CENTS', '');
        if (expectedGrossTotalCents && expectedGrossTotalCents.length > 0) {
          check(c, {
            'grossCentsTotal matches expected': (x) => String(x.grossCentsTotal) === String(expectedGrossTotalCents),
          });
        }
      }
    }
  }

  sleep(0.1);
}
