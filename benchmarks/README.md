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

### Worker HR-backed benchmark endpoint (includes HR callbacks + tax + labor + time-derived overtime)
This is the recommended macrobench when you want to include:
- HR reads (pay period + employee snapshots + garnishment orders)
- tax-service calls (federal + state rules)
- labor-service calls
- time-derived overtime (via time-ingestion-service)
- HR withholding callbacks (`/garnishments/withholdings`)

Phase A / Phase B recommended flow (production-like)
- Phase A (compute): compute paychecks and store them (benchmark in-memory store)
- Phase B (render): render pay statements/check artifacts from stored paychecks

This mirrors typical payroll pipelines and avoids benchmarking huge HTTP responses.

Enable the endpoint (disabled by default):
- `WORKER_BENCHMARKS_ENABLED=true`
- `WORKER_BENCHMARKS_TOKEN=dev-secret`

Enable time-derived overtime (recommended default macrobench path):
- set `DOWNSTREAMS_TIME_ENABLED=true` for worker/orchestrator (in docker compose or your `bootRun` env)
- seed time entries with `SEED_TIME=true` when running `./benchmarks/seed/seed-benchmark-data.sh`

Tip pooling (worksite-level) for benchmarks:
- When `SEED_TIME=true`, the seed script assigns hourly employees to worksites (e.g. `BAR`, `DINING`) and seeds cash/charged tips.
- time-ingestion-service computes `allocatedTipsCents` using a tip allocation rule.
- For `EMP-BENCH`, the default rule pools 10% of charged tips per worksite and allocates it by hours.

Additional earnings scenarios (seeded via time-ingestion-service):
- When `SEED_TIME=true`, the seed script also seeds deterministic per-period amounts for a subset of employees:
  - `commissionCents` (maps to earning code `COMMISSION`)
  - `bonusCents` (maps to earning code `BONUS`)
  - `reimbursementNonTaxableCents` (maps to earning code `EXP_REIMB`)

Cohort knobs (HR seed):
- `MI_EVERY` (default 5): every Nth employee is MI hourly (with MI city localities)
- `NY_EVERY` (default 7): every Nth employee is NY hourly
- `CA_HOURLY_EVERY` (default 9): every Nth employee is CA hourly (intended to hit CA daily OT/DT + 7th-day rules)
- `TIPPED_EVERY` (default 11): every Nth employee is marked as a tipped employee (tip-credit logic only applies to hourly employees). Tip amounts are sourced from time-ingestion-service when `SEED_TIME=true`.
- `GARNISHMENT_EVERY` (default 3): every Nth employee receives one+ active garnishment order

Run (after seeding) (range-based):
- You can omit `EMPLOYEE_ID_END`; k6 will call the worker endpoint which queries hr-service and returns both:
  - the full inferred end from HR
  - a "safe" end capped to `worker.benchmarks.maxEmployeeIdsPerRequest`

Single-phase (compute-only, small response):
- `k6 run -e WORKER_URL=http://localhost:8088 -e BENCH_TOKEN=dev-secret -e EMPLOYER_ID=EMP-BENCH -e PAY_PERIOD_ID=2025-01-BW1 -e EMPLOYEE_ID_PREFIX=EE-BENCH- -e EMPLOYEE_ID_START=1 -e VERIFY_SEED=true benchmarks/k6/worker-hr-backed-pay-period.js`

Two-phase (recommended):
1) Phase A: compute + store
- Use a stable `RUN_ID` so Phase B can reference it.
- `k6 run -e WORKER_URL=http://localhost:8088 -e BENCH_TOKEN=dev-secret -e EMPLOYER_ID=EMP-BENCH -e PAY_PERIOD_ID=2025-01-BW1 -e EMPLOYEE_ID_PREFIX=EE-BENCH- -e EMPLOYEE_ID_START=1 -e EMPLOYEE_ID_END=200 -e RUN_ID=bench-run-2025-01-BW1 benchmarks/k6/worker-hr-backed-pay-period-store.js`
2) Phase B: render statements (optionally include JSON serialization cost)
- `k6 run -e WORKER_URL=http://localhost:8088 -e BENCH_TOKEN=dev-secret -e EMPLOYER_ID=EMP-BENCH -e RUN_ID=bench-run-2025-01-BW1 -e SERIALIZE_JSON=true benchmarks/k6/worker-render-pay-statements.js`
- Optionally also generate a wide CSV (one paycheck per row, pay elements as columns) and include its byte size in the response:
  - `k6 run -e WORKER_URL=http://localhost:8088 -e BENCH_TOKEN=dev-secret -e EMPLOYER_ID=EMP-BENCH -e RUN_ID=bench-run-2025-01-BW1 -e SERIALIZE_JSON=true -e GENERATE_CSV=true benchmarks/k6/worker-render-pay-statements.js`

CSV export (download)
- `curl -H 'X-Benchmark-Token: dev-secret' -H 'Content-Type: application/json' \
    -d '{"runId":"bench-run-2025-01-BW1"}' \
    'http://localhost:8088/benchmarks/employers/EMP-BENCH/render-paychecks.csv' \
    -o paychecks.csv`

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
- Basic connectivity:
  - `curl -H 'X-Benchmark-Token: dev-secret' 'http://localhost:8088/benchmarks/employers/EMP-BENCH/seed-verification?payPeriodId=2025-01-BW1&employeeId=EE-BENCH-000001'`
- Include a full computed paycheck payload (single employee) for inspection:
  - `curl -H 'X-Benchmark-Token: dev-secret' 'http://localhost:8088/benchmarks/employers/EMP-BENCH/seed-verification?payPeriodId=2025-01-BW1&employeeId=EE-BENCH-000001&includePaycheck=true'`

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
- Orchestrator internal auth must be configured (JWT keyring), and k6 must send an internal JWT signed with the configured key.
- HR/Tax/Labor dependencies must be reachable and have data seeded for the chosen employer/payPeriod/employeeIds.

Enterprise-grade seeding approach (recommended)
- Treat benchmark data like an environment bootstrap step (analogous to a Kubernetes Job):
  - schema migrations
  - deterministic seed data
  - repeatable imports
- Use the *real* tax + labor importers so the DB content matches what production would use.

Local dev flow (legacy execute-loop):
1. Start the full stack (Postgres + services) so Flyway migrations run:
   - `DOWNSTREAMS_TIME_ENABLED=true docker compose up -d`
2. Seed HR + import tax + import labor + (recommended) seed time entries:
- `EMPLOYER_ID=EMP-BENCH PAY_PERIOD_ID=2025-01-BW1 CHECK_DATE=2025-01-15 EMPLOYEE_COUNT=200 MI_EVERY=5 NY_EVERY=7 CA_HOURLY_EVERY=9 TIPPED_EVERY=11 GARNISHMENT_EVERY=3 SEED_TIME=true TIME_BASE_URL=http://localhost:8084 ./benchmarks/seed/seed-benchmark-data.sh`
3. Enable legacy execute endpoint on orchestrator (bench/dev only):
   - set `orchestrator.payrun.execute.enabled=true`
4. Run the k6 macrobench:
   - `k6 run -e ORCH_URL=http://localhost:8085 -e INTERNAL_JWT='<jwt>' -e EMPLOYER_ID=EMP-BENCH -e PAY_PERIOD_ID=2025-01-BW1 -e EMPLOYEE_IDS=EE-BENCH-000001,EE-BENCH-000002 benchmarks/k6/orchestrator-finalize-execute.js`

### Orchestrator finalize via RabbitMQ (queue-driven, parallel)
This is the enterprise-style path and the recommended way to benchmark parallelism:
- Orchestrator enqueues one job per employee into RabbitMQ (via outbox)
- Multiple worker replicas consume jobs concurrently
- Orchestrator finalizer transitions the payrun to terminal when all items complete

Run (compose + N worker replicas):
1. Start the stack with RabbitMQ enabled (time-derived overtime enabled):
   - `DOWNSTREAMS_TIME_ENABLED=true DOWNSTREAMS_ORCHESTRATOR_INTERNAL_JWT_SECRET=dev-internal-token docker compose -f docker-compose.yml -f docker-compose.override.yml -f docker-compose.bench-parallel.yml up -d --build`
2. Scale workers:
   - `docker compose -f docker-compose.yml -f docker-compose.override.yml -f docker-compose.bench-parallel.yml up -d --scale payroll-worker-service=4`
3. Seed HR + import tax + import labor + (recommended) seed time entries:
- `EMPLOYER_ID=EMP-BENCH PAY_PERIOD_ID=2025-01-BW1 CHECK_DATE=2025-01-15 EMPLOYEE_COUNT=1000 MI_EVERY=5 NY_EVERY=7 CA_HOURLY_EVERY=9 TIPPED_EVERY=11 GARNISHMENT_EVERY=3 SEED_TIME=true TIME_BASE_URL=http://localhost:8084 ./benchmarks/seed/seed-benchmark-data.sh`
4. Run k6 (single payrun wall-clock):
   - `k6 run -e ORCH_URL=http://localhost:8085 -e EMPLOYER_ID=EMP-BENCH -e PAY_PERIOD_ID=2025-01-BW1 -e EMPLOYEE_ID_PREFIX=EE-BENCH- -e EMPLOYEE_ID_START=1 -e EMPLOYEE_COUNT=1000 benchmarks/k6/orchestrator-rabbit-finalize.js`

Or use the helper script to sweep worker replica counts and write a report:
- `EMPLOYEE_COUNT=1000 WORKER_REPLICAS_CSV=1,2,4,8 ./benchmarks/run-parallel-payrun-bench.sh`
- Output directory (default): `/tmp/us-payroll-parallel-bench/`

Include Phase B (render artifacts after finalize):
- `EMPLOYEE_COUNT=1000 WORKER_REPLICAS_CSV=1,2,4,8 RENDER_AFTER_FINALIZE=true BENCH_TOKEN=dev-secret ./benchmarks/run-parallel-payrun-bench.sh`
- Optional toggles:
  - `RENDER_SERIALIZE_JSON=true|false`
  - `RENDER_GENERATE_CSV=true|false`
  - `RENDER_LIMIT=1000` (cap renders; default is orchestrator.benchmarks.maxPaychecksPerRequest)

Verbosity controls (to prevent terminal buffer issues):
- Minimal output mode (disables Postgres statistics collection):
  - `BENCH_MINIMAL_OUTPUT=true ./benchmarks/run-parallel-payrun-bench.sh`
- Reduce Postgres diagnostics sampling (default: every 3rd sample):
  - `PG_DIAG_SAMPLE_EVERY=10 ./benchmarks/run-parallel-payrun-bench.sh`
  - Set to 0 to disable diagnostics entirely
- Redirect output to file (recommended for long runs):
  - `./benchmarks/run-parallel-payrun-bench.sh > /tmp/bench.log 2>&1 &`
  - `tail -f /tmp/bench.log`

Run:
- `k6 run -e ORCH_URL=http://localhost:8086 -e INTERNAL_JWT='<jwt>' -e EMPLOYER_ID=emp-1 -e PAY_PERIOD_ID=2025-01-BW1 -e EMPLOYEE_IDS=ee-1,ee-2 benchmarks/k6/orchestrator-finalize-execute.js`

Tuning:
- `-e VUS=4` `-e DURATION=30s` (defaults)
- `-e BATCH_SIZE=25` `-e MAX_ITEMS=200` `-e MAX_MILLIS=2000`
