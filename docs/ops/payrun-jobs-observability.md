# Payrun jobs observability (RabbitMQ)
This guide covers operational monitoring for the queue-driven, per-employee payrun finalization flow.
It includes example Prometheus alert rules and suggested Grafana panels using the Micrometer metric names emitted by:
- `payroll-worker-service` (job consumer + queue gauges)
- `payroll-orchestrator-service` (per-item finalize endpoint)

## Architecture context
- Orchestrator enqueues one job per employee into RabbitMQ via the outbox (`destination_type=RABBIT`).
- Worker replicas consume per-employee jobs and call orchestrator’s internal per-item endpoint:
  - `POST /employers/{employerId}/payruns/internal/{payRunId}/items/{employeeId}/finalize`
- Retries are modeled as TTL retry queues; failures beyond the retry budget land in a DLQ.

## Key metrics
### Worker (job consumer)
Counters:
- `worker.payrun.finalize_employee.total{outcome="succeeded"}`
- `worker.payrun.finalize_employee.total{outcome="failed_terminal"}`
- `worker.payrun.finalize_employee.total{outcome="retry_enqueued"}`
- `worker.payrun.finalize_employee.total{outcome="dlq"}`
- `worker.payrun.finalize_employee.total{outcome="client_error"}`

Timers:
- `worker.payrun.finalize_employee.duration` (Micrometer timer)

Gauges:
- `worker.rabbit.queue.depth{queue="<queue>"}`
  - includes main queue, retry queues, and DLQ

DLQ replay tooling:
- `worker.dlq.replay.total{queue="<dlq>"}`

### Orchestrator (per-item finalize)
Counters:
- `orchestrator.payrun.item.finalize.total{outcome="succeeded"}`
- `orchestrator.payrun.item.finalize.total{outcome="retryable"}`
- `orchestrator.payrun.item.finalize.total{outcome="failed"}`
- `orchestrator.payrun.item.finalize.total{outcome="noop"}`

Timers:
- `orchestrator.payrun.item.finalize.duration` (Micrometer timer)

## Suggested Grafana panels (PromQL)
The PromQL examples below assume Prometheus scraping Micrometer metrics in the usual Prometheus format.
You may need to adapt label names like `job`, `application`, `namespace`, etc. depending on your Prometheus setup.

### Queue backlog
Main queue depth:
- Query:
  - `worker.rabbit.queue.depth{queue="payrun-finalize-employee-jobs"}`

DLQ depth:
- Query:
  - `worker.rabbit.queue.depth{queue="payrun-finalize-employee-jobs-dlq"}`

Retry queue depth (sum):
- Query:
  - `sum(worker.rabbit.queue.depth{queue=~"payrun-finalize-employee-retry-.*"})`

### Throughput and error rates
Succeeded per minute:
- Query:
  - `sum(rate(worker_payrun_finalize_employee_total{outcome="succeeded"}[5m])) * 60`

Terminal failures per minute:
- Query:
  - `sum(rate(worker_payrun_finalize_employee_total{outcome="failed_terminal"}[5m])) * 60`

Retry enqueues per minute:
- Query:
  - `sum(rate(worker_payrun_finalize_employee_total{outcome="retry_enqueued"}[5m])) * 60`

DLQ inflow per minute:
- Query:
  - `sum(rate(worker_payrun_finalize_employee_total{outcome="dlq"}[5m])) * 60`

Terminal failure ratio (5m):
- Query:
  - `sum(rate(worker_payrun_finalize_employee_total{outcome="failed_terminal"}[5m]))
     /
     clamp_min(sum(rate(worker_payrun_finalize_employee_total{outcome=~"succeeded|failed_terminal"}[5m])), 1e-9)`

### Latency
Worker-side orchestration latency (p95), if Prometheus has Micrometer histogram buckets enabled:
- Query:
  - `histogram_quantile(0.95, sum by (le) (rate(worker_payrun_finalize_employee_duration_seconds_bucket[5m])))`

Orchestrator per-item finalize latency (p95):
- Query:
  - `histogram_quantile(0.95, sum by (le) (rate(orchestrator_payrun_item_finalize_duration_seconds_bucket[5m])))`

If you do not have histogram buckets enabled, fall back to average duration:
- Worker average duration:
  - `rate(worker_payrun_finalize_employee_duration_seconds_sum[5m])
     /
     clamp_min(rate(worker_payrun_finalize_employee_duration_seconds_count[5m]), 1e-9)`
- Orchestrator average duration:
  - `rate(orchestrator_payrun_item_finalize_duration_seconds_sum[5m])
     /
     clamp_min(rate(orchestrator_payrun_item_finalize_duration_seconds_count[5m]), 1e-9)`

## Example Prometheus alert rules
See `docs/ops/prometheus-alerts-payrun-jobs.yaml`.

## DLQ replay (operator workflow)
This repository includes a worker-only DLQ replay endpoint (disabled by default):
- `POST /internal/jobs/finalize-employee/dlq/replay?maxMessages=100&resetAttempt=true`

Operational guidance:
- Only replay after you’ve addressed the root cause (bad config, downstream outage, etc.).
- Prefer replaying in small batches and watch:
  - `worker.rabbit.queue.depth{queue="payrun-finalize-employee-jobs-dlq"}`
  - `worker.payrun.finalize_employee.total{outcome="retry_enqueued"}`
  - `worker.payrun.finalize_employee.total{outcome="failed_terminal"}`

## Notes on tier-1 payroll expectations
- Transport duplicates can happen; the system’s idempotency guarantees should make duplicates a no-op at the business boundary.
- Alerts should focus on:
  - backlog (can’t keep up)
  - terminal failures (needs human intervention)
  - elevated retry rates (degraded dependencies)
  - latency regressions
