# Reporting/filings paycheck ledger events

This document defines the stable event contract used by reporting/filings consumers to build a paycheck ledger without coupling to orchestrator internal tables.

## Producer
Produced by `payroll-orchestrator-service` when a pay run is **approved**.

- One event is emitted per **succeeded** paycheck in the approved pay run.
- The producer uses the orchestrator outbox; events are published idempotently.

## Topic
Default Kafka topic:
- `paycheck.ledger`

(Overridable via `orchestrator.events.kafka.paycheck-ledger-topic`.)

## Event type
Outbox header / envelope `event_type`:
- `PaycheckLedger`

Payload class (in `messaging-core`):
- `com.example.uspayroll.messaging.events.reporting.PaycheckLedgerEvent`

## Semantics
`PaycheckLedgerEvent.action` defines the ledger operation:
- `COMMITTED`: approved paychecks for normal pay runs (including REGULAR and OFF_CYCLE)
- `VOIDED`: approved paychecks for VOID pay runs (negative amounts)
- `ADJUSTED`: approved paychecks for ADJUSTMENT pay runs (delta amounts)
- `REISSUED`: approved paychecks for REISSUE pay runs (cloned amounts)

Downstream consumers should treat `paycheckId` as the unique event aggregate, and use `correctionOfPaycheckId` / `correctionOfPayRunId` to link reversals/deltas to the original paycheck.

## Idempotency and ordering
- Deterministic `eventId` format:
  - `paycheck-ledger:<action>:<employerId>:<paycheckId>`
- Partition key:
  - `<employerId>:<employeeId>`

Consumers should still implement inbox de-duplication (recommended: `messaging-core` `JdbcEventInbox`).

## Payload contents (high level)
The payload is designed to be stable and reporting-friendly:
- stable identifiers: employerId, employeeId, payRunId, payRunType, runSequence, payPeriodId, paycheckId
- ISO dates: periodStartIso, periodEndIso, checkDateIso
- totals: currency, grossCents, netCents
- filing aggregates: `audit` (mirrors `PaycheckAudit` stable fields for taxable wage bases and totals)
- optional line items: earnings, employeeTaxes, employerTaxes, deductions, employerContributions

See source:
- `messaging-core/src/main/kotlin/com/example/uspayroll/messaging/events/reporting/PaycheckLedgerEvents.kt`
