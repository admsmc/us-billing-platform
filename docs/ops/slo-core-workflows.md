# SLOs for core payroll workflows
This document defines suggested SLIs/SLOs and alerting guidance for the most important workflows.

All thresholds here are starting points. Tune them using real production traffic and error budgets.

## Conventions
- Prometheus metric names below assume Micrometer’s Prometheus naming conventions.
- Some latency queries rely on histogram buckets. If you don’t have `_bucket` series, enable histograms for the relevant timers.

## Workflow: Payrun finalize (queue-driven)
### SLI: per-employee finalize error rate
Signals:
- Orchestrator per-item finalize outcome counter:
  - `orchestrator_payrun_item_finalize_total{outcome=...}`

Suggested SLO (starting point):
- 99.9% of per-employee finalizations end in `succeeded` over 30 days (error budget includes retryable failures that eventually succeed).

PromQL examples:
- Terminal failure rate (5m):
  - `sum(rate(orchestrator_payrun_item_finalize_total{outcome="failed"}[5m]))
     /
     clamp_min(sum(rate(orchestrator_payrun_item_finalize_total{outcome=~"succeeded|failed"}[5m])), 1e-9)`

Alerting guidance:
- Page if terminal failure rate > 0.5% for 10m.
- Ticket if terminal failure rate > 0.1% for 30m.

### SLI: per-employee finalize latency
Signal:
- `orchestrator_payrun_item_finalize_duration_seconds_*`

PromQL examples:
- p95 latency (requires histograms):
  - `histogram_quantile(0.95, sum by (le) (rate(orchestrator_payrun_item_finalize_duration_seconds_bucket[5m])))`
- Average latency (fallback):
  - `rate(orchestrator_payrun_item_finalize_duration_seconds_sum[5m])
     /
     clamp_min(rate(orchestrator_payrun_item_finalize_duration_seconds_count[5m]), 1e-9)`

Suggested SLO (starting point):
- p95 < 2s for per-employee finalize.

### SLI: backlog / inability to keep up
Signals:
- Worker queue depth gauges:
  - `worker_rabbit_queue_depth{queue="payrun-finalize-employee-jobs"}`
  - `worker_rabbit_queue_depth{queue="payrun-finalize-employee-jobs-dlq"}`

Alerting guidance:
- Page if main queue depth is monotonically increasing for 15m and exceeds an absolute threshold (tune per employer size).
- Page if DLQ depth increases for 5m.

## Workflow: Payment initiation
This repo treats payments-service as system-of-record; orchestrator maintains a projection.

### SLI: payment request enqueue success
Signals:
- Use the payrun initiate endpoint response metrics once added, or (interim) use outbox relay failure rates.

Suggested SLO (starting point):
- 99.9% of payment initiation requests succeed (HTTP 2xx) over 30 days.

## Operational links
- Payrun jobs observability:
  - `docs/ops/payrun-jobs-observability.md`
- DLQ replay + reconciliation:
  - `docs/ops/dlq-replay-reconciliation.md`
- Tracing local setup:
  - `docs/ops/tracing-local.md`
