# Benchmarks
This repo supports two kinds of performance benchmarking:

## 1) Microbenchmarks (JMH): payroll core
Goal: measure the pure compute cost of paycheck computation (warm JVM, no HTTP/DB).

Run:
- `./scripts/gradlew-java21.sh :payroll-benchmarks:jmh --no-daemon`

Notes:
- Results are written under `payroll-benchmarks/build/results/jmh/`.
- The benchmark class is `com.example.uspayroll.benchmarks.PaycheckComputationBenchmark`.

## 2) Macrobenchmarks (k6): HTTP endpoint
Goal: measure service-level latency/throughput including Spring/JSON.

### Worker dry-run endpoint
This is a lightweight baseline that does not require HR/Tax/Labor services.

### Worker HR-backed benchmark endpoint (includes HR callbacks + tax + labor)
This is the recommended macrobench when you want to include:
- HR reads (pay period + employee snapshots + garnishment orders)
- tax-service calls
- labor-service calls
- HR withholding callbacks (`/garnishments/withholdings`)

Enable the endpoint (disabled by default):
- `WORKER_BENCHMARKS_ENABLED=true`
- `WORKER_BENCHMARKS_TOKEN=dev-secret`

Run (after seeding) (range-based):
- You can omit `EMPLOYEE_ID_END`; k6 will call the worker endpoint which queries hr-service and returns both:
  - the full inferred end from HR
  - a "safe" end capped to `worker.benchmarks.maxEmployeeIdsPerRequest`
- `k6 run -e WORKER_URL=http://localhost:8088 -e BENCH_TOKEN=dev-secret -e EMPLOYER_ID=EMP-BENCH -e PAY_PERIOD_ID=2025-01-BW1 -e EMPLOYEE_ID_PREFIX=EE-BENCH- -e EMPLOYEE_ID_START=1 -e VERIFY_SEED=true benchmarks/k6/worker-hr-backed-pay-period.js`

Optional: lightweight correctness check (digest + aggregates)
- The HR-backed benchmark endpoint can optionally compute a deterministic correctness summary (no full paycheck payload).
- Enable it in k6 with any of:
  - `-e CORRECTNESS_MODE=digest`
  - `-e EXPECTED_DIGEST_XOR=<number>`
  - `-e EXPECTED_NET_TOTAL_CENTS=<number>`
  - `-e EXPECTED_GROSS_TOTAL_CENTS=<number>`

Locking in expected values (baseline)
1. Run once with digest enabled (example):
   - `k6 run -e WORKER_URL=http://localhost:8088 -e BENCH_TOKEN=dev-secret -e EMPLOYER_ID=EMP-BENCH -e PAY_PERIOD_ID=2025-01-BW1 -e EMPLOYEE_ID_PREFIX=EE-BENCH- -e EMPLOYEE_ID_START=1 -e EMPLOYEE_ID_END=200 -e CORRECTNESS_MODE=digest benchmarks/k6/worker-hr-backed-pay-period.js`
2. Capture the returned `correctness.digestXor` and (optionally) `correctness.netCentsTotal` / `correctness.grossCentsTotal`.
3. Re-run with expectations set to turn it into a fast regression guardrail:
   - `k6 run ... -e EXPECTED_DIGEST_XOR=<copied> -e EXPECTED_NET_TOTAL_CENTS=<copied> benchmarks/k6/worker-hr-backed-pay-period.js`

Recommended range discovery:
- `curl -H 'X-Benchmark-Token: dev-secret' 'http://localhost:8088/benchmarks/employers/EMP-BENCH/employee-id-range?prefix=EE-BENCH-&padWidth=6&startInclusive=1'`

Note:
- For range discovery to work, hr-service must enable its internal benchmark endpoint:
  - `HR_BENCHMARKS_ENABLED=true`
  - `HR_BENCHMARKS_TOKEN=dev-secret` (optional but recommended)

Seed verification (read-only):
- `curl -H 'X-Benchmark-Token: dev-secret' 'http://localhost:8088/benchmarks/employers/EMP-BENCH/seed-verification?payPeriodId=2025-01-BW1&employeeId=EE-BENCH-000001'`

1. Start worker-service:
   - `./scripts/gradlew-java21.sh :payroll-worker-service:bootRun`
2. In another terminal, run k6:
   - `k6 run -e BASE_URL=http://localhost:8080 benchmarks/k6/worker-dry-run-paychecks.js`

Tuning:
- `-e VUS=32` (virtual users)
- `-e DURATION=60s`

Example:
- `k6 run -e BASE_URL=http://localhost:8080 -e VUS=32 -e DURATION=60s benchmarks/k6/worker-dry-run-paychecks.js`

### Orchestrator finalize + internal execute (legacy execute-loop)
This exercises the older time-sliced execution loop:
- `POST /employers/{employerId}/payruns/finalize` (create payrun)
- `POST /employers/{employerId}/payruns/internal/{payRunId}/execute` (time-sliced execution loop)
- `GET  /employers/{employerId}/payruns/{payRunId}` (final status)

Note:
- This endpoint is now gated behind `orchestrator.payrun.execute.enabled=true` and is intended for benchmarks/dev only.

Prereqs:
- Orchestrator service must be running.
- Orchestrator internal auth must be configured (non-blank shared secret), and k6 must send the same token.
- HR/Tax/Labor dependencies must be reachable and have data seeded for the chosen employer/payPeriod/employeeIds.

Enterprise-grade seeding approach (recommended)
- Treat benchmark data like an environment bootstrap step (analogous to a Kubernetes Job):
  - schema migrations
  - deterministic seed data
  - repeatable imports
- Use the *real* tax + labor importers so the DB content matches what production would use.

Local dev flow (legacy execute-loop):
1. Start the full stack (Postgres + services) so Flyway migrations run:
   - `docker compose up -d`
2. Seed HR + import tax + import labor:
   - `EMPLOYER_ID=EMP-BENCH PAY_PERIOD_ID=2025-01-BW1 CHECK_DATE=2025-01-15 EMPLOYEE_COUNT=200 MI_EVERY=5 GARNISHMENT_EVERY=3 ./benchmarks/seed/seed-benchmark-data.sh`
3. Enable legacy execute endpoint on orchestrator (bench/dev only):
   - set `orchestrator.payrun.execute.enabled=true`
4. Run the k6 macrobench:
   - `k6 run -e ORCH_URL=http://localhost:8085 -e INTERNAL_TOKEN=dev-secret -e EMPLOYER_ID=EMP-BENCH -e PAY_PERIOD_ID=2025-01-BW1 -e EMPLOYEE_IDS=EE-BENCH-000001,EE-BENCH-000002 benchmarks/k6/orchestrator-finalize-execute.js`

### Orchestrator finalize via RabbitMQ (queue-driven, parallel)
This is the enterprise-style path and the recommended way to benchmark parallelism:
- Orchestrator enqueues one job per employee into RabbitMQ (via outbox)
- Multiple worker replicas consume jobs concurrently
- Orchestrator finalizer transitions the payrun to terminal when all items complete

Run (compose + N worker replicas):
1. Start the stack with RabbitMQ enabled:
   - `ORCHESTRATOR_INTERNAL_AUTH_SHARED_SECRET=dev-internal-token docker compose -f docker-compose.yml -f docker-compose.override.yml -f docker-compose.bench-parallel.yml up -d --build`
2. Scale workers:
   - `docker compose -f docker-compose.yml -f docker-compose.override.yml -f docker-compose.bench-parallel.yml up -d --scale payroll-worker-service=4`
3. Seed HR + import tax + import labor:
   - `EMPLOYER_ID=EMP-BENCH PAY_PERIOD_ID=2025-01-BW1 CHECK_DATE=2025-01-15 EMPLOYEE_COUNT=1000 MI_EVERY=5 GARNISHMENT_EVERY=3 ./benchmarks/seed/seed-benchmark-data.sh`
4. Run k6 (single payrun wall-clock):
   - `k6 run -e ORCH_URL=http://localhost:8085 -e EMPLOYER_ID=EMP-BENCH -e PAY_PERIOD_ID=2025-01-BW1 -e EMPLOYEE_ID_PREFIX=EE-BENCH- -e EMPLOYEE_ID_START=1 -e EMPLOYEE_COUNT=1000 benchmarks/k6/orchestrator-rabbit-finalize.js`

Or use the helper script to sweep worker replica counts and write a report:
- `EMPLOYEE_COUNT=1000 WORKER_REPLICAS_CSV=1,2,4,8 ./benchmarks/run-parallel-payrun-bench.sh`
- Output directory (default): `/tmp/us-payroll-parallel-bench/`

Run:
- `k6 run -e ORCH_URL=http://localhost:8086 -e INTERNAL_TOKEN=dev-secret -e EMPLOYER_ID=emp-1 -e PAY_PERIOD_ID=2025-01-BW1 -e EMPLOYEE_IDS=ee-1,ee-2 benchmarks/k6/orchestrator-finalize-execute.js`

Tuning:
- `-e VUS=4` `-e DURATION=30s` (defaults)
- `-e BATCH_SIZE=25` `-e MAX_ITEMS=200` `-e MAX_MILLIS=2000`
