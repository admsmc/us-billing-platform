# Enterprise readiness capability backlog (Step 8)

This backlog is the prioritized set of capabilities required to move the platform from “payroll engine prototype” to “enterprise payroll system”.

Priorities:
- **P0**: required for first enterprise production deployment
- **P1**: required shortly after go-live or for larger customers
- **P2**: roadmap / scale features

Each item includes acceptance criteria that are intended to be testable (often by golden tests).

## Epic A — HR system of record: persistence, auditability, and effective dating

### A0 (P0) HR write API + persistence for employee profile
Owner: `hr-service`

Problem:
- HR reads exist (snapshots and pay periods), but enterprise readiness requires authoritative writes and an audit trail.

Acceptance criteria:
- Provide endpoints to create/update employee profile data (at minimum: home/work location, filing status/W-4 inputs, employment type flags).
- Changes are persisted and reflected in `EmployeeSnapshotProvider.getEmployeeSnapshot(..., asOfDate)`.
- Every write produces an **append-only audit record** with:
  - who/what performed the change (actor type + actor id)
  - when it was recorded (system time)
  - effective date (valid time)
  - a stable change id / correlation id
  - before/after payload (or patch) that is safe for audit (PII handling policy applies)
- As-of reads are deterministic: querying the same asOfDate against the same DB state returns the same snapshot.

### A1 (P0) Effective-dated employee attributes (beyond compensation)
Owner: `hr-service`

Acceptance criteria:
- Support effective-dated updates for attributes that drive payroll correctness:
  - work/home state & locality (as used for tax/labor selection)
  - filing status / W-4 parameters
  - FICA exempt, nonresident alien, tipped employee, FLSA exempt status
- Define conflict rules for overlapping effective ranges (reject overlaps or resolve by precedence).
- Provide query semantics:
  - effective_from <= asOfDate < effective_to
- Backfill/migration strategy is documented.

### A2 (P0) Pay period management as a first-class workflow
Owner: `hr-service`

Acceptance criteria:
- Ability to create/update pay schedules and generate pay periods.
- Prevent invalid pay periods (overlaps, gaps if disallowed, invalid check dates).
- Provide idempotent creation (idempotency key) for period generation.
- Worker/orchestrator can find pay period by check date reliably.

### A3 (P1) Bitemporal HR (valid time + system time) OR explicitly-defined audit semantics
Owner: `hr-service`

Acceptance criteria:
- Either:
  - bitemporal model supported for key entities, or
  - explicitly document that system-time corrections are performed by appending compensating records and never mutating history.
- Provide “as-of system time” access for audits (optional if append-only audit is sufficient).

## Epic B — Tax & labor content governance and approvals

### B0 (P0) Content metadata + traceability standard
Owner: `tax-content`, `tax-service`, `labor-service`

Acceptance criteria:
- Every curated content artifact (CSV/JSON) includes or references metadata:
  - authoritative source identifier (publication / statute)
  - year and effective date range
  - revision date
  - checksum of source material (where applicable)
  - internal approval references (SME sign-off)
- Metadata is versioned in Git and traceable to a commit SHA.

### B1 (P0) Formal approval workflow for content changes
Owner: repo process + CI

Acceptance criteria:
- Changes under `tax-content/src/main/resources` and labor standards inputs require:
  - designated reviewers (e.g., CODEOWNERS for compliance/SME)
  - a PR template section capturing source + sign-off
- CI enforces:
  - generated artifacts are up to date (CSV -> JSON -> SQL)
  - validators and golden tests pass

### B2 (P0) Yearly update playbooks expanded to state tax + labor
Owner: `tax-service`, `labor-service`

Acceptance criteria:
- Maintain and extend ops playbooks to cover:
  - state income tax rule updates (rules + brackets)
  - labor standards annual updates
- Each playbook defines:
  - sources of truth
  - deterministic generation steps
  - SME review checkpoints
  - promotion/import steps and audit logging

Playbooks:
- `docs/ops/yearly-update-playbook-state-income-tax.md`
- `docs/ops/yearly-update-playbook-labor-standards.md`

### B3 (P1) Coverage expansion plan (state/local)
Owner: `tax-service`, `labor-service`

Acceptance criteria:
- Produce a state/local coverage matrix and identify:
  - supported (tax withholding, unemployment, local taxes)
  - partially supported
  - unsupported
- Include sequencing for expanding coverage.

Plan:
- `docs/ops/coverage-expansion-plan-state-local.md`

## Epic C — Enterprise payroll workflows

### C0 (P0) Off-cycle pay runs
Owner: `payroll-orchestrator-service`, `payroll-worker-service`

Acceptance criteria:
- Support `PayRunType.OFF_CYCLE` end-to-end:
  - create/run/approve/pay
  - ability to include supplemental earnings (bonuses/commissions)
- Clear rules for tax treatment (supplemental vs regular) and traceability in paycheck audit.
- Golden tests exist for representative off-cycle scenarios.

### C1 (P0) Corrections: void / reissue
Owner: orchestrator + payments + reporting

Acceptance criteria:
- Support `PayRunType.VOID` and `PayRunType.REISSUE` semantics:
  - void reverses wages/taxes in reporting/YTD according to defined accounting model
  - reissue produces a new paycheckId with linkage to original
- Idempotent APIs and invariants documented.
- Golden workflow test covers create -> pay -> void -> reissue.

### C2 (P0) Retro pay driven by effective-dated HR changes
Owner: HR + worker + orchestrator

Acceptance criteria:
- When effective-dated compensation or tax-affecting attributes change in the past:
  - system can compute delta (“retro”) for impacted periods
  - system can generate adjustment run(s) deterministically
- The retro algorithm is deterministic and auditable:
  - inputs are fingerprinted (employee snapshot + tax context + labor standards)
  - replay yields identical deltas
- Golden tests include retro scenarios (rate change mid-quarter; location change; W-4 change).

### C3 (P1) Multi-jurisdiction employment scenarios
Owner: HR + tax + labor + worker

Acceptance criteria:
- Support employees who:
  - live and work in different states
  - work in multiple localities within a pay period
- Explicit locality selection semantics (already partially covered in tax golden tests).

## Epic D — Payments reconciliation and downstream integration points

### D0 (P0) Payment reconciliation contract
Owner: `payments-service`, `payroll-orchestrator-service`

Acceptance criteria:
- Define a stable reconciliation model:
  - payment batch identifiers
  - payment status transitions
  - linkage to paychecks/pay runs
- Provide admin/reporting endpoints to:
  - reconcile mismatches
  - rebuild projections from source of truth (already exists for some flows)

### D1 (P1) Bank file / payment provider integration seam
Owner: `payments-service`

Acceptance criteria:
- Define a port interface for payment providers (ACH/wire) and a sandbox implementation.
- Ensure sensitive data handling and encryption boundaries are documented.

## Epic E — Filings and reporting integration points

### E0 (P0) Reporting/filings event contract
Owner: `reporting-service`, `filings-service`, domain

Acceptance criteria:
- Define events or exports that represent:
  - committed paycheck
  - voided paycheck
  - adjusted paycheck
- Include sufficient fields for filing preparation without coupling to internal tables.

### E1 (P1) Quarterly/annual filing “shape”
Owner: `filings-service`

Acceptance criteria:
- Define the initial filing artifacts and APIs:
  - 941/940 (federal)
  - W-2/W-3
  - state withholding summaries
- Include validation hooks and reconciliation with payments.

## Golden tests (cross-epic requirement)

P0 items must be backed by golden tests in the appropriate layer(s):
- Domain golden tests for pure calculation invariants.
- Catalog golden tests for tax/labor rule interpretation.
- Workflow golden tests for off-cycle/correction/retro + reconciliation.
