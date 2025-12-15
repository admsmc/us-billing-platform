# Pay run corrections: VOID and REISSUE

This document describes the current accounting model for pay run corrections implemented in `payroll-orchestrator-service`.

## Goals
- Support `PayRunType.VOID` and `PayRunType.REISSUE` end-to-end.
- Preserve deterministic, idempotent correction artifacts.
- Provide traceability from correction pay runs/paychecks back to the source.

## Current accounting model
The system models corrections as *new pay runs* and *new paychecks* that reference an original pay run/paycheck.

- **VOID** is modeled as a new paycheck whose monetary amounts are the negation of the original paycheck (earnings, taxes, deductions, net, and YTD maps).
- **REISSUE** is modeled as a new paycheck that clones the original paycheck amounts but receives a new `paycheckId`.

Downstream reporting/YTD should treat paychecks as a ledger and compute aggregates by summing paychecks (including negative void paychecks).

## Traceability
- `pay_run.correction_of_pay_run_id` links the correction pay run to the source pay run.
- `paycheck.correction_of_paycheck_id` links the correction paycheck to the source paycheck.

## API
These endpoints create correction pay runs synchronously:
- `POST /employers/{employerId}/payruns/{payRunId}/void`
- `POST /employers/{employerId}/payruns/{payRunId}/reissue`

Both accept an optional request body:
- `requestedPayRunId`
- `runSequence`
- `idempotencyKey`

## Invariants
- Source pay run must be `APPROVED` for both VOID and REISSUE.
- VOID additionally requires the source pay run to be fully `PAID`.
- `POST /payruns/{payRunId}/payments/initiate` is disallowed for `PayRunType.VOID`.

## Known limitations
- Correction runs currently clone existing paychecks/audits rather than recomputing from HR/Tax/Labor. This keeps corrections deterministic and avoids changing historical inputs during disputes.
- The payroll engine currently persists YTD snapshots as per-paycheck deltas in the orchestrator-backed flow (prior YTD is not loaded from historical paychecks). The VOID implementation negates the stored `ytdAfter` maps accordingly.
