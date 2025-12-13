import http from 'k6/http';
import { check, sleep } from 'k6';

// Run example:
//   k6 run -e BASE_URL=http://localhost:8080 benchmarks/k6/worker-dry-run-paychecks.js

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-vus',
      vus: __ENV.VUS ? Number(__ENV.VUS) : 16,
      duration: __ENV.DURATION || '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(50)<50', 'p(95)<200'],
  },
};

export default function () {
  const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
  const url = `${baseUrl}/dry-run-paychecks`;

  const res = http.get(url, {
    headers: {
      'Accept': 'application/json',
    },
  });

  check(res, {
    'status is 200': (r) => r.status === 200,
  });

  // Small sleep to avoid perfectly synchronized request bursts.
  sleep(0.1);
}
