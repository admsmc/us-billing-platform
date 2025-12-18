import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const computeMs = new Trend('worker_hr_backed_compute_store_ms');

export const options = {
  scenarios: {
    once: {
      executor: 'per-vu-iterations',
      vus: __ENV.VUS ? Number(__ENV.VUS) : 1,
      iterations: __ENV.ITERATIONS ? Number(__ENV.ITERATIONS) : 1,
      maxDuration: __ENV.MAX_DURATION || '10m',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

function env(name, def) {
  return __ENV[name] && __ENV[name].length > 0 ? __ENV[name] : def;
}

export default function () {
  const workerUrl = env('WORKER_URL', 'http://localhost:8088');
  const employerId = env('EMPLOYER_ID', 'EMP-BENCH');
  const payPeriodId = env('PAY_PERIOD_ID', '2025-01-BW1');

  const headerName = env('BENCH_HEADER', 'X-Benchmark-Token');
  const token = env('BENCH_TOKEN', '');

  const prefix = env('EMPLOYEE_ID_PREFIX', 'EE-BENCH-');
  const pad = Number(env('EMPLOYEE_ID_PAD', '6'));
  const start = Number(env('EMPLOYEE_ID_START', '1'));
  const end = Number(env('EMPLOYEE_ID_END', '200'));

  // IMPORTANT: use a stable RUN_ID so phase B can reference it.
  const runId = env('RUN_ID', `bench-run-${payPeriodId}`);

  const url = `${workerUrl}/benchmarks/employers/${employerId}/hr-backed-pay-period-store`;

  const payload = {
    payPeriodId,
    employeeIdPrefix: prefix,
    employeeIdStartInclusive: start,
    employeeIdEndInclusive: end,
    employeeIdPadWidth: pad,
    runId,
    correctnessMode: env('CORRECTNESS_MODE', ''),
  };

  const startedAt = new Date().getTime();
  const res = http.post(url, JSON.stringify(payload), {
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
    computeMs.add(new Date().getTime() - startedAt);
  }

  sleep(0.1);
}
