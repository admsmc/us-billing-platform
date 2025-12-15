# HR bitemporal + audit semantics (A3)

This document defines how HR data is corrected over time while keeping payroll calculations deterministic and auditable.

## Concepts

### Valid time (business effective time)
Valid time answers: “What was true for the employee as of a payroll-relevant date?”

We model valid time using inclusive/exclusive ranges:
- `effective_from <= asOfDate < effective_to`

Examples:
- A work location change effective 2025-02-01 should affect paychecks whose as-of date falls on/after 2025-02-01.

### System time (recorded time)
System time answers: “What did the system believe at a given point in time?”

We model system time using inclusive/exclusive ranges:
- `system_from <= systemAsOf < system_to`

The “current” version (what runtime services use) is defined by:
- `system_from <= CURRENT_TIMESTAMP AND system_to > CURRENT_TIMESTAMP`

## Tables

### `employee_profile_effective`
- Valid time: `effective_from`, `effective_to`
- System time: `system_from`, `system_to`

### `employment_compensation`
- Valid time: `effective_from`, `effective_to`
- System time: `system_from`, `system_to`

## Write semantics (no in-place mutation)

For effective-dated entities that participate in bitemporal history:
- HR **never updates a row in place**.
- Instead it:
  1. **Supersedes** the current system-time version by setting `system_to = CURRENT_TIMESTAMP`.
  2. **Inserts** a new row representing the corrected version (new `system_from`, open-ended `system_to`).

This provides:
- deterministic “current” snapshots
- complete system-time history of corrections

Valid-time changes (e.g. splitting a segment) are also captured by inserting new rows (and superseding prior versions).

## Audit trail (`hr_audit_event`)
All write operations emit an append-only audit record.

- The bitemporal tables preserve system-time history of the *facts*.
- The audit event log preserves *intent and context* (who/what/why), plus before/after payloads.

## Reading semantics

### Runtime payroll snapshot reads
Runtime snapshot queries use:
- the requested valid-time `asOfDate`
- the current system-time window (`CURRENT_TIMESTAMP`) to select the active version

In other words, payroll calculations are deterministic given a fixed DB state.

### Future: system-time as-of reads (optional)
We can extend read endpoints to support `systemAsOf` parameters for audits and backtesting.
