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

### Benchmark profiles

The seed script now supports high-level benchmark profiles via `PROFILE`:

- `PROFILE=stress` (default)
  - Original high-coverage mix used by existing docs and k6 scripts.
  - Smaller employee population (default `EMPLOYEE_COUNT=200`).
  - Dense garnishments (default `GARNISHMENT_EVERY=3` → ~1/3 of employees with at least one order).
  - Filing status: all `SINGLE`, 0 dependents (as before).
- `PROFILE=realistic`
  - Larger, more typical population (default `EMPLOYEE_COUNT=400`).
  - Lower garnishment density (default `GARNISHMENT_EVERY=30`).
  - More realistic filing status mix (approximate):
    - ~50% `SINGLE`
    - ~30% `MARRIED_FILING_JOINTLY`
    - ~10% `HEAD_OF_HOUSEHOLD`
    - ~10% `MARRIED_FILING_SEPARATELY`
  - More realistic dependents mix when `realistic_profile=1` in `hr_seed.sql`:
    - Many employees with 0–1 dependents, some with 2–3.
  - Salaried pay bands widened to include lower annual salaries (25k–60k) alongside mid/high bands (80k–200k).

Run the seed script with a profile like:

- Realistic:
  - `PROFILE=realistic EMPLOYER_ID=EMP-BENCH PAY_PERIOD_ID=2025-01-BW1 CHECK_DATE=2025-01-15 ./benchmarks/seed/seed-benchmark-data.sh`
- Stress (legacy behavior):
  - `PROFILE=stress EMPLOYER_ID=EMP-BENCH PAY_PERIOD_ID=2025-01-BW1 CHECK_DATE=2025-01-15 ./benchmarks/seed/seed-benchmark-data.sh`

### Correctness digest baseline for the realistic profile

Once you are happy with the realistic profile, you can lock in a correctness baseline
for macrobench runs that use it. This turns the k6 macrobench into a fast regression
check for both totals and the detailed paycheck shape.

1. Seed using the realistic profile (example):
   - `PROFILE=realistic EMPLOYER_ID=EMP-BENCH PAY_PERIOD_ID=2025-01-BW1 CHECK_DATE=2025-01-15 EMPLOYEE_COUNT=400 SEED_TIME=true TIME_BASE_URL=http://localhost:8084 ./benchmarks/seed/seed-benchmark-data.sh`
2. Run the HR-backed worker macrobench once with correctness mode enabled (range-based):
   - `k6 run -e WORKER_URL=http://localhost:8088 -e BENCH_TOKEN=dev-secret -e EMPLOYER_ID=EMP-BENCH -e PAY_PERIOD_ID=2025-01-BW1 -e EMPLOYEE_ID_PREFIX=EE-BENCH- -e EMPLOYEE_ID_START=1 -e EMPLOYEE_ID_END=400 -e CORRECTNESS_MODE=digest benchmarks/k6/worker-hr-backed-pay-period.js`
3. Capture the returned correctness summary fields from the response body (printed or logged by k6):
   - `correctness.digestXor`
   - `correctness.grossCentsTotal`
   - `correctness.netCentsTotal`
4. Re-run with expectations set to enforce the baseline (example):
   - `k6 run -e WORKER_URL=http://localhost:8088 -e BENCH_TOKEN=dev-secret -e EMPLOYER_ID=EMP-BENCH -e PAY_PERIOD_ID=2025-01-BW1 -e EMPLOYEE_ID_PREFIX=EE-BENCH- -e EMPLOYEE_ID_START=1 -e EMPLOYEE_ID_END=400 -e EXPECTED_DIGEST_XOR=<copied> -e EXPECTED_GROSS_TOTAL_CENTS=<copiedGross> -e EXPECTED_NET_TOTAL_CENTS=<copiedNet> benchmarks/k6/worker-hr-backed-pay-period.js`

This guards against accidental changes to the macrobench population, tax rules, or
calculation engine that would materially change gross/tax/net outcomes for the
realistic profile.

### Representative employees and approximate net/gross ranges (realistic profile)

The exact amounts will depend on current-year tax rules, but you can use the
following representative employees (seeded by `hr_seed.sql`) as sanity checks.
Use `/seed-verification` with `includePaycheck=true` plus the CSV export endpoints
to inspect full details when needed.

Examples (approximate expectations only):

- **Low-wage MI hourly worker**
  - ID pattern: an `EE-BENCH-` employee where `n % mi_every == 0` and `PROFILE=realistic`.
  - Typical config: MI work state, hourly $20–$24, ~42h in the period (40 regular + small overtime).
  - Expected gross (before tax): roughly 40h * base rate + 2h overtime at 1.5x.
  - Expected net: typically **65–80%** of gross once federal + state + FICA and any garnishments are applied.

- **Mid-range CA salaried worker**
  - ID pattern: salaried CA employee (not in MI/NY/CA-hourly cohorts), salary in the 50k–80k band.
  - Typical config: `SINGLE`, 0–1 dependents, BIWEEKLY pay (~26 checks/year).
  - Expected per-paycheck gross: annual salary / 26.
  - Expected net: typically **60–80%** of gross depending on bracket, state/local taxes, and benefits.

- **Tipped CA hourly worker**
  - ID pattern: CA hourly where `n % tipped_every == 0` (tipped flag true) with `SEED_TIME=true`.
  - Typical config: CA work state, hourly $30–$36, 7 consecutive days including long shifts,
    cash + charged tips seeded per day.
  - Expected behavior:
    - Significant portion of income as tips (cash + charged + allocated).
    - Net percentage highly sensitive to pooled tip allocations and withholding, but gross
      should align with (hours * hourly rate + tips).

- **Employee with child-support garnishment**
  - IDs: use the helper at the end of `seed-benchmark-data.sh`, which prints the first
    couple of employees with garnishments based on `GARNISHMENT_EVERY`.
  - Typical config: MI issuing jurisdiction, child support order (up to 60% of disposable)
    with a protected floor to exercise protected-earnings behavior.
  - Expected behavior:
    - Deductions should show one or two `ORDER-BENCH-...` lines per paycheck.
    - Net should *never* go below zero and should respect the protected floor when
      multiple orders apply.

For each of these, you can:

- Call seed verification with paycheck included:
  - `curl -H 'X-Benchmark-Token: dev-secret' 'http://localhost:8088/benchmarks/employers/EMP-BENCH/seed-verification?payPeriodId=2025-01-BW1&employeeId=EE-BENCH-000120&includePaycheck=true'`
- Export CSV for manual inspection:
  - `curl -H 'X-Benchmark-Token: dev-secret' -H 'Content-Type: application/json' -d '{"runId":"bench-run-2025-01-BW1"}' 'http://localhost:8088/benchmarks/employers/EMP-BENCH/render-paychecks.csv' -o paychecks.csv`

These checks, combined with the correctness digest baseline, give you both a
quantitative and qualitative view of whether macrobench paychecks remain
plausible as the system evolves.

1. Start worker-service:
   - `./scripts/gradlew-java21.sh :payroll-worker-service:bootRun`
2. In another terminal, run k6:
   - `k6 run -e BASE_URL=http://localhost:8080 benchmarks/k6/worker-dry-run-paychecks.js`

Tuning:
- `-e VUS=32` (virtual users)
- `-e DURATION=60s`

Example:
- `k6 run -e BASE_URL=http://localhost:8080 -e VUS=32 -e DURATION=60s benchmarks/k6/worker-dry-run-paychecks.js`

## Configuration Approach

Queue-driven benchmarks use Spring Boot profiles for configuration instead of scattered environment variables.

### Benchmark Profile
The `benchmark` profile is defined in:
- `payroll-orchestrator-service/src/main/resources/application-benchmark.yml`
- `payroll-worker-service/src/main/resources/application-benchmark.yml`

This profile configures:
- Internal JWT authentication (orchestrator ↔ worker)
- RabbitMQ settings
- PayRun finalizer settings
- Benchmark endpoints

Activate via `SPRING_PROFILES_ACTIVE=benchmark` (already set in docker-compose.bench-parallel.yml).

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

## Benchmark Results

### Queue-Driven Parallel Payrun Performance (December 2023)

These results demonstrate the system's throughput characteristics across different worker replica counts and employee population sizes. All tests were run with RabbitMQ-based job distribution, Postgres persistence, and the full service stack (HR, Tax, Labor).

#### Test Configuration
- Profile: `stress` (default)
- Services: Full stack with time-ingestion-service enabled
- Database: Postgres 16 (docker)
- Message broker: RabbitMQ 3
- JVM: Java 21

#### Results Summary

**1,000 Employees:**

| Workers | Time (p50) | Throughput | Scaling Factor |
|---------|------------|------------|----------------|
| 1       | 16.9s      | 59 emp/sec | 1.0x           |
| 2       | 7.3s       | 136 emp/sec| 2.3x           |
| **4**   | **7.1s**   | **141 emp/sec** | **2.4x**  |
| 8       | 10.3s      | 97 emp/sec | 1.6x           |

**5,000 Employees:**

| Workers | Time | Throughput |
|---------|------|------------|
| **4**   | **41s** | **122 emp/sec** |

**10,000 Employees:**

| Workers | Time | Throughput | vs 4 Workers |
|---------|------|------------|-------------|
| **4**   | **42.4s** | **236 emp/sec** | Baseline |
| 8       | 69.9s     | 143 emp/sec     | 39% slower |

**50,000 Employees:**

| Workers | Time | Throughput | Notes |
|---------|------|------------|-------|
| **4**   | **171s (2.85 min)** | **292 emp/sec** | 24% faster than 10K |

**60,000 Employees (near PostgreSQL parameter limit):**

| Workers | Time | Throughput | vs 4 Workers |
|---------|------|------------|-------------|
| **4**   | **216s (3.6 min)** | **278 emp/sec** | Baseline |
| 8       | 351s (5.85 min)    | 171 emp/sec     | 38% slower |

#### Key Findings

1. **Optimal configuration: 4 workers**
   - Best throughput across all workload sizes (1K-10K employees)
   - Maintains consistent performance as workload scales
   - Sweet spot between parallelism and coordination overhead

2. **Superlinear scaling from 5K to 50K employees**
   - 5K: 122 emp/sec
   - 10K: 236 emp/sec (93% improvement)
   - 50K: 292 emp/sec (24% improvement over 10K)
   - System benefits from sustained high throughput (warm JVM, connection pooling, caching)
   - No degradation with larger datasets

3. **8 workers consistently underperform**
   - 39% slower than 4 workers at 10K employees
   - Database contention becomes bottleneck (CPU utilization 57%+)
   - RabbitMQ coordination overhead exceeds parallelism benefits
   - Recommendation: avoid over-provisioning workers

4. **Production capacity estimate**
   - 10,000-employee payrun: 42 seconds with 4 workers
   - 50,000-employee payrun: 171 seconds (2.85 minutes) with 4 workers
   - 60,000-employee payrun: 216 seconds (3.6 minutes) with 4 workers
   - **Current maximum**: ~60K employees due to PostgreSQL parameter limit
   - Suitable for most enterprise payroll processing windows

#### Recommendations

- **Development/staging**: Use 2-4 worker replicas
- **Production**: Start with 4 workers, monitor database CPU and RabbitMQ metrics before scaling up
- **Large enterprises (20K+ employees)**: Consider sharding by employer or pay group rather than adding more workers
- **Database tuning**: Connection pool sizing is critical; 8 workers hit connection limits

### Phase 2 Scaling Improvements (December 2024)

Phase 2 delivers connection pooling and database performance optimizations to support 8-16+ workers and 100K+ employee payruns.

#### Improvements Delivered

1. **Batched INSERT operations**
   - Removes PostgreSQL 65K parameter limit
   - Chunks payrun item INSERTs into 10K-row batches
   - Enables 100K+ employee payruns
   - No performance regression for smaller workloads

2. **PgBouncer connection pooling**
   - Transaction-mode pooling (optimal for high concurrency)
   - Default pool size: 20 connections per database
   - Max client connections: 2000
   - Eliminates connection exhaustion with 8+ workers
   - **Enabled by default in benchmarks** (set `BENCH_DISABLE_PGBOUNCER=true` to disable)

3. **Performance indexes**
   - Covering index for count queries (O(1) lookups)
   - Partial indexes for QUEUED/RUNNING/FAILED items
   - Worker claim operations optimization
   - Fast failure reporting

#### Expected Performance with Phase 2

**60K employees with 8 workers:**
- Before Phase 2: 351s (171 emp/sec) - 38% slower than 4 workers
- After Phase 2: ~120-150s (400-500 emp/sec) - 2-3x improvement
- Connection pooling eliminates database contention bottleneck

**Architecture Support:**
- Phase 1+2: 100K employees in 6-8 minutes (4-8 workers)
- With Phase 3 (planned): 500K in 8-10 minutes (read replicas + Redis)
- With Phase 4 (planned): 1M+ employees (partitioned payruns)

See `docs/architecture/scale-to-hundreds-of-thousands.md` for the complete scaling roadmap.
- **Recommended fix**: Implement batched INSERT statements or use PostgreSQL COPY for bulk inserts

#### Future Improvements

- **Critical**: Implement batched payrun item insertion to support 100K+ employees
- Investigate database query optimization to reduce contention at 8+ workers
- Consider read replicas for HR/Tax/Labor service queries
- Explore connection pooling strategies (e.g., PgBouncer) for higher worker counts
- Add Redis caching layer for tax rules and labor standards
- Support streaming or filter-based employee selection for very large payruns

Last updated: 2024-12-23

Run:
- `k6 run -e ORCH_URL=http://localhost:8086 -e INTERNAL_JWT='<jwt>' -e EMPLOYER_ID=emp-1 -e PAY_PERIOD_ID=2025-01-BW1 -e EMPLOYEE_IDS=ee-1,ee-2 benchmarks/k6/orchestrator-finalize-execute.js`

Tuning:
- `-e VUS=4` `-e DURATION=30s` (defaults)
- `-e BATCH_SIZE=25` `-e MAX_ITEMS=200` `-e MAX_MILLIS=2000`
