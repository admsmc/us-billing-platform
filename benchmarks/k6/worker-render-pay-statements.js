import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const renderMs = new Trend('worker_render_pay_statements_ms');

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
  },
};

function env(name, def) {
  return __ENV[name] && __ENV[name].length > 0 ? __ENV[name] : def;
}

function truthy(v) {
  if (!v) return false;
  return String(v).toLowerCase() === 'true' || String(v) === '1';
}

export default function () {
  const workerUrl = env('WORKER_URL', 'http://localhost:8088');
  const employerId = env('EMPLOYER_ID', 'EMP-BENCH');

  const headerName = env('BENCH_HEADER', 'X-Benchmark-Token');
  const token = env('BENCH_TOKEN', '');

  const runId = env('RUN_ID', '');
  if (!runId) {
    throw new Error('RUN_ID is required for render phase (must match Phase A RUN_ID)');
  }

  const url = `${workerUrl}/benchmarks/employers/${employerId}/render-pay-statements`;

  const payload = {
    runId,
    serializeJson: truthy(env('SERIALIZE_JSON', 'true')),
    generateCsv: truthy(env('GENERATE_CSV', 'false')),
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
    renderMs.add(new Date().getTime() - startedAt);
  }

  sleep(0.1);
}
