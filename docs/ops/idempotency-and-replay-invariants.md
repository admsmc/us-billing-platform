# Idempotency and replay invariants
This document is the single source of truth for the invariants that make retries, duplicates, DLQ replay, and outbox replays safe.

These invariants are intentionally short and audit-friendly. If a change violates an invariant, treat it as a correctness bug.

## Invariant 1: All integration events must have a stable eventId
Duplicates are expected (retries, rebalances, network failures). Therefore:
- All Kafka events MUST have a stable `eventId` (same logical event → same `eventId`).
- When publishing Kafka messages, the producer MUST also set `X-Event-Id` (used for consumer de-dupe).

Producer-side support:
- The outbox supports deterministic publishing by persisting `outbox_event.event_id` and enforcing uniqueness.
  - See `payroll-orchestrator-service/src/main/resources/db/migration/orchestrator/V009__unique_outbox_event_id.sql`.

## Invariant 2: Kafka consumers must use inbox.runIfFirst
All Kafka message handlers MUST be wrapped in:
- `JdbcEventInbox.runIfFirst(consumerName, eventId) { ... }`

Properties of `runIfFirst` in this repo:
- It inserts `(consumer, event_id)` into `event_inbox` and skips processing on duplicates.
- If the handler throws, it best-effort removes the inbox marker, so the event can be retried (prevents “mark then crash => lost processing”).

Reference implementation:
- `messaging-core/src/main/kotlin/com/example/uspayroll/messaging/inbox/EventInbox.kt`

## Invariant 3: Outbox relays must be safe under duplicate ticks
Outbox relays MUST be correct if:
- `tick()` is called concurrently (multiple replicas),
- `tick()` is called twice for the same rows,
- the process crashes after publish but before marking sent,
- publish partially succeeds within a batch.

Mechanisms used in this repo:
- Relays claim rows via `OutboxRepository.claimBatch(...)` which transitions `PENDING -> SENDING` under a DB lock.
- The claim generates a lease token (`locked_at`) that must be echoed back to `markSent` / `markFailed`.
  - This prevents a stale relay instance from “finishing” someone else’s claim.
- Failures transition back to `PENDING` with `attempts++` and `next_attempt_at` set.

Reference:
- `payroll-orchestrator-service/src/main/kotlin/com/example/uspayroll/orchestrator/outbox/OutboxRepository.kt`
- Relay implementations:
  - `payroll-orchestrator-service/src/main/kotlin/com/example/uspayroll/orchestrator/outbox/OutboxRelay.kt` (Kafka)
  - `payroll-orchestrator-service/src/main/kotlin/com/example/uspayroll/orchestrator/outbox/RabbitOutboxRelay.kt` (Rabbit)

## Invariant 4: Client-facing POST operations that create work are idempotent via Idempotency-Key
At the HTTP boundary, any POST that creates durable work MUST accept a client-supplied idempotency key, and the server MUST enforce it.

In this repo:
- Header name is standardized as `Idempotency-Key` (see `web-core/src/main/kotlin/com/example/uspayroll/web/WebHeaders.kt`).
- If both header and body carry an idempotency key, they must match (otherwise reject).

## Invariant 5: Deterministic business effects are guarded by unique constraints
When possible, business-side idempotency is enforced by database uniqueness.

Key constraints used for idempotency in this repo:
- Payrun start idempotency:
  - `pay_run (employer_id, requested_idempotency_key)` unique.
  - See `payroll-orchestrator-service/src/main/resources/db/migration/orchestrator/V002__create_payrun_tables.sql`.
- Payment initiation idempotency:
  - `pay_run (employer_id, payment_initiate_idempotency_key)` unique.
  - See `payroll-orchestrator-service/src/main/resources/db/migration/orchestrator/V015__add_payment_initiate_idempotency_key.sql`.
- Paycheck slot uniqueness (prevents duplicate paychecks for the same slot):
  - `paycheck (employer_id, employee_id, pay_period_id, run_type, run_sequence)` unique.
  - See `payroll-orchestrator-service/src/main/resources/db/migration/orchestrator/V006__add_paycheck_slot_key.sql`.
- Payment-per-paycheck uniqueness:
  - `paycheck_payment (employer_id, paycheck_id)` unique.
  - See:
    - `payments-service/src/main/resources/db/migration/V003__create_paycheck_payment.sql`
    - `payroll-orchestrator-service/src/main/resources/db/migration/orchestrator/V008__create_paycheck_payment.sql`
- Consumer inbox uniqueness:
  - `event_inbox (consumer, event_id)` primary key.
  - See:
    - `messaging-core/src/main/kotlin/com/example/uspayroll/messaging/inbox/EventInbox.kt` (recommended schema)
    - service migrations such as `payroll-orchestrator-service/src/main/resources/db/migration/orchestrator/V010__create_event_inbox.sql`.

## Audit pointers
When reviewing a PR that touches messaging, retries, or replay paths:
- For any Kafka consumer changes: verify `runIfFirst` is used and the eventId is stable.
- For any new outbox usage: prefer a deterministic `event_id` where duplicates would be harmful.
- For any new “create work” POST endpoint: require and enforce `Idempotency-Key`.
- For any new irreversible business effect: consider a unique constraint or deterministic key to make retries safe.
