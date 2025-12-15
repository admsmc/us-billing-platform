# Incident response (payroll workflows)
This runbook is a symptom-driven playbook for diagnosing and recovering from incidents.

## Quick triage checklist
1) Is it an availability problem or a correctness problem?
- Availability: backlog, elevated latency, timeouts
- Correctness: duplicate business effects, inconsistent projections, stuck runs

2) Determine blast radius
- One employer vs many
- One service vs multiple

3) Identify the failing hop
- Use traces to find where time is spent (edge → worker → orchestrator → HR/Tax/Labor).
- Use logs (correlationId/traceId) to pinpoint the error.

## Symptom: growing Rabbit backlog
Signals:
- `worker_rabbit_queue_depth{queue="payrun-finalize-employee-jobs"}`
- `worker_rabbit_queue_depth{queue="payrun-finalize-employee-jobs-dlq"}`

Likely causes:
- worker replicas down or blocked
- orchestrator per-item finalize latency regression
- downstream dependency outage (HR/Tax/Labor)

Actions:
- Check worker job outcomes:
  - `worker_payrun_finalize_employee_total{outcome=...}`
- If retry rate is elevated, prioritize dependency restoration.
- If terminal failures accumulate, stop replay and move to reconciliation.

## Symptom: DLQ growing
Rule of thumb:
- Treat sustained DLQ growth as paging-worthy: it indicates durable work that needs intervention.

Actions:
- Do not replay blindly.
- Use `docs/ops/dlq-replay-reconciliation.md` to decide replay vs reconcile.

## Symptom: payruns stuck in RUNNING
Actions:
- Use internal endpoints documented in `docs/ops/dlq-replay-reconciliation.md`:
  - list stuck runs
  - requeue stale/running/failed items
  - recompute status / finalize-and-enqueue-events

## Symptom: payments stuck / projection drift
Actions:
- Prefer repairing the source of truth first (payments-service health, Kafka delivery).
- Use orchestrator internal tools:
  - recompute payment status from paychecks
  - rebuild payment projections from payments-service (optional)

See:
- `docs/ops/dlq-replay-reconciliation.md`

## Using traces effectively
- Run local tracing backend:
  - `docs/ops/tracing-local.md`
- Always start from the edge-service span and follow downstream.
- For slow requests, identify the span with the highest self-time; check its logs by traceId.
