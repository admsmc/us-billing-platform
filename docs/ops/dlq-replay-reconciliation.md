# DLQ replay and reconciliation runbook
This document defines the operational rules for replaying dead-lettered messages and reconciling partial failures.

The goal is to make retries and replays safe by default via idempotency and deterministic business effects.

## Terminology
- Work queue (RabbitMQ): per-employee payrun finalization jobs.
- DLQ: dead-letter queue for messages that exceeded their retry budget.
- Retry queues: TTL queues that delay re-delivery and then route back to the main queue.
- Inbox: consumer-side dedupe table (`event_inbox`) keyed by `(consumer, event_id)`.
- Outbox: producer-side durable queue table (`outbox_event`).

## Core invariants (must hold)
For the canonical, audit-friendly list of invariants (stable event IDs, inbox usage, outbox relay semantics, and uniqueness constraints), see:
- `docs/ops/idempotency-and-replay-invariants.md`

SLOs and incident response:
- `docs/ops/slo-core-workflows.md`
- `docs/ops/incident-response.md`

Tracing setup:
- `docs/ops/tracing-local.md`

High-level reminder:
- Duplicate deliveries are expected.
- Business effects must be idempotent.
- Retries must not silently drop work.

## Rabbit payrun finalization flow (queue-driven)
- Orchestrator enqueues one job per employee into RabbitMQ via the outbox (`destination_type=RABBIT`).
- Worker consumes a job and calls orchestrator’s internal endpoint:
  - `POST /employers/{employerId}/payruns/internal/{payRunId}/items/{employeeId}/finalize`

### Retry semantics
- Retryable failures are re-published to a TTL retry routing key.
- After the retry budget is exhausted, the job is published to the DLQ.

### Safe replay criteria
DLQ replay is safe when all of the following are true:
- The root cause is understood and addressed (bad config fixed, downstream dependency restored, bug fixed).
- The orchestrator per-item finalize endpoint remains idempotent for the same `(employerId, payRunId, employeeId)`.
- Replaying a message cannot create duplicate paychecks or double-apply any irreversible side effects.

If any of these are not true, do not replay automatically; use reconciliation instead.

### Recommended replay procedure
1) Validate that the root cause is fixed.
2) Replay in small batches.
3) Watch backlog and error metrics:
- `worker.rabbit.queue.depth{queue="payrun-finalize-employee-jobs-dlq"}`
- `worker.payrun.finalize_employee.total{outcome="retry_enqueued"}`
- `worker.payrun.finalize_employee.total{outcome="failed_terminal"}`

4) If DLQ refills quickly, stop and re-diagnose.

Executable drill (compose-first):
- `scripts/ops/drills/dlq-replay-drill.sh`

This script produces a JSON drill artifact and demonstrates a full loop:
- induce a controlled failure
- observe DLQ growth
- fix the root cause
- replay DLQ and confirm recovery

## Kafka consumer replay rules (inbox-backed)
Kafka replay can happen due to:
- consumer restarts
- rebalances
- manual offset resets

### Rules
- All events must have a stable `eventId`.
- Consumers should process via the inbox helper:
  - `inbox.runIfFirst(consumerName, eventId) { ... }`
- If the handler throws, the inbox marker is removed so the event can be retried.

## Reconciliation workflows
Use reconciliation when:
- the failure mode is not safely replayable
- partial business effects may have been applied
- duplicates are not guaranteed to be safe

### Payrun reconciliation (orchestrator)
Suggested checks:
- Identify payruns stuck in `RUNNING` with no recent progress.
- Compare expected employee set vs `pay_run_item` statuses.
- For missing or stuck items, requeue per-employee jobs (prefer queue-driven paths).

This repo includes internal endpoints (guarded by an internal JWT via `Authorization: Bearer <token>`) under:
- `/employers/{employerId}/payruns/internal/...`

Common actions:
- List potentially stuck runs:
  - `GET /employers/{employerId}/payruns/internal/reconcile/stuck-running?olderThanMillis=600000`
- Ensure all QUEUED items have Rabbit jobs enqueued:
  - `POST /employers/{employerId}/payruns/internal/{payRunId}/reconcile/requeue-queued`
- Requeue stale RUNNING items (based on `updated_at`) and enqueue missing jobs:
  - `POST /employers/{employerId}/payruns/internal/{payRunId}/reconcile/requeue-stale-running?staleMillis=600000`
- Operator retry of FAILED items:
  - `POST /employers/{employerId}/payruns/internal/{payRunId}/reconcile/requeue-failed?limit=1000&reason=...`
- Force finalize + enqueue payrun/paycheck finalized events (when items are already terminal but the scheduled finalizer was down):
  - `POST /employers/{employerId}/payruns/internal/{payRunId}/reconcile/finalize-and-enqueue-events`
- Recompute pay_run status from item counts without publishing:
  - `POST /employers/{employerId}/payruns/internal/{payRunId}/reconcile/recompute-run-status`
  - Optional dry-run mode:
    - `POST /employers/{employerId}/payruns/internal/{payRunId}/reconcile/recompute-run-status?persist=false`

### Payments reconciliation
Suggested checks:
- For a given `(employerId, payRunId)`, ensure there is a payment record per succeeded paycheck.
- If payment initiation was attempted, verify outbox events exist for all payment requests.
- If needed, re-run payment initiation using an `Idempotency-Key`.

This repo includes internal helpers:
- Re-enqueue missing payment request events:
  - `POST /employers/{employerId}/payruns/internal/{payRunId}/reconcile/payments/re-enqueue-requests`
- Recompute `pay_run.payment_status` from paycheck counts (repairs projection drift):
  - `POST /employers/{employerId}/payruns/internal/{payRunId}/reconcile/payments/recompute-run-status`
- (Optional) Rebuild orchestrator payment projections from payments-service (source-of-truth):
  - `POST /employers/{employerId}/payruns/internal/{payRunId}/reconcile/payments/rebuild-projection`
  - Safety constraints (defaults): payrun must be finalized + approved, and all succeeded paychecks must have a payment row in payments-service.
  - Dry-run mode:
    - `.../rebuild-projection?persist=false`

## Escalation guidelines
- If DLQ volume grows steadily: treat as an incident (SLO impact).
- If many messages fail terminally with the same error: stop replay and fix the systemic cause.
- If duplicates cause incorrect results: treat as a correctness bug and block further replay until fixed.
