# Enterprise readiness capability register

This register tracks enterprise-readiness capabilities and their delivery status.

Status:
- **Done**: implemented and operating end-to-end (may still have small follow-ups)
- **Partial**: meaningful implementation exists but coverage is incomplete (scope, tests, docs, or integrations)
- **Not Started**: no meaningful implementation yet

Target priority (deployment criticality):
- **P0**: required for first enterprise production deployment
- **P1**: required shortly after go-live or for larger customers
- **P2**: roadmap / scale features

Each capability includes acceptance criteria intended to be testable (often by golden tests).

## Summary (as of 2025-12-16)

### Done
- A0 HR write API + persistence for employee profile
- A1 Effective-dated employee attributes (beyond compensation)
- A2 Pay period management as a first-class workflow
- A3 Bitemporal HR (valid time + system time) OR explicitly-defined audit semantics
- B0 Content metadata + traceability standard
- B1 Formal approval workflow for content changes
- B2 Yearly update playbooks expanded to state tax + labor
- B3 Coverage expansion plan (state/local)
- C0 Off-cycle pay runs
- C1 Corrections: void / reissue
- C2 Retro pay driven by effective-dated HR changes
- D0 Payment reconciliation contract
- D1 Bank file / payment provider integration seam (sandbox provider)
- E0 Reporting/filings event contract
- E1 Quarterly/annual filing “shape” (initial shape APIs)

### Partial
- C3 Multi-jurisdiction employment scenarios
- X0 Golden tests coverage

### Not Started
- (none currently listed)

## Epic A — HR system of record: persistence, auditability, and effective dating

### A0 HR write API + persistence for employee profile
Target priority: P0
Owner: `hr-service`

Status (as of 2025-12-16): Done (verify remaining minor gaps)
Evidence:
- Write endpoints: `hr-service/src/main/kotlin/com/example/uspayroll/hr/http/HrWriteController.kt`
- Audit log: `hr-service/src/main/kotlin/com/example/uspayroll/hr/audit/HrAuditService.kt` + `hr-service/src/main/resources/db/migration/hr/V009__create_hr_audit_event.sql`
- Idempotency: `hr-service/src/main/kotlin/com/example/uspayroll/hr/idempotency/HrIdempotencyService.kt`

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

### A1 Effective-dated employee attributes (beyond compensation)
Target priority: P0
Owner: `hr-service`

Status (as of 2025-12-16): Done
Evidence:
- Effective-dated profile store and bitemporal support:
  - `hr-service/src/main/resources/db/migration/hr/V008__create_employee_profile_effective.sql`
  - `hr-service/src/main/resources/db/migration/hr/V012__bitemporal_employee_profile_effective.sql`
- Effective-dated update logic (segment split + system-time supersede):
  - `hr-service/src/main/kotlin/com/example/uspayroll/hr/employee/HrEmployeeRepository.kt`

Acceptance criteria:
- Support effective-dated updates for attributes that drive payroll correctness:
  - work/home state & locality (as used for tax/labor selection)
  - filing status / W-4 parameters
  - FICA exempt, nonresident alien, tipped employee, FLSA exempt status
- Define conflict rules for overlapping effective ranges (reject overlaps or resolve by precedence).
- Provide query semantics:
  - effective_from <= asOfDate < effective_to
- Backfill/migration strategy is documented.

### A2 Pay period management as a first-class workflow
Target priority: P0
Owner: `hr-service`

Status (as of 2025-12-16): Done
Evidence:
- Manual pay period creation with validation + idempotency:
  - `hr-service/src/main/kotlin/com/example/uspayroll/hr/http/HrWriteController.kt` (`PUT /pay-periods/{payPeriodId}`)
- Pay schedule upsert + deterministic pay period generation with validation + idempotency:
  - `hr-service/src/main/kotlin/com/example/uspayroll/hr/http/HrPayScheduleWriteController.kt` (`POST /pay-schedules/{scheduleId}/generate-pay-periods`)
- Validation rules documented:
  - `docs/ops/hr-pay-period-management.md`

Acceptance criteria:
- Ability to create/update pay schedules and generate pay periods.
- Prevent invalid pay periods (overlaps, gaps if disallowed, invalid check dates).
- Provide idempotent creation (idempotency key) for period generation.
- Worker/orchestrator can find pay period by check date reliably.

### A3 Bitemporal HR (valid time + system time) OR explicitly-defined audit semantics
Target priority: P1
Owner: `hr-service`

Status (as of 2025-12-16): Done
Evidence:
- Design doc:
  - `docs/ops/hr-bitemporal-and-audit-semantics.md`
- Migrations:
  - `hr-service/src/main/resources/db/migration/hr/V012__bitemporal_employee_profile_effective.sql`
  - `hr-service/src/main/resources/db/migration/hr/V013__bitemporal_employment_compensation.sql`

Acceptance criteria:
- Either:
  - bitemporal model supported for key entities, or
  - explicitly document that system-time corrections are performed by appending compensating records and never mutating history.
- Provide “as-of system time” access for audits (optional if append-only audit is sufficient).

## Epic B — Tax & labor content governance and approvals

### B0 Content metadata + traceability standard
Target priority: P0
Owner: `tax-content`, `tax-service`, `labor-service`

Status (as of 2025-12-16): Done
Evidence:
- Standard:
  - `docs/ops/content-metadata-traceability-standard.md`
- Sidecar metadata files (examples):
  - `tax-content/src/main/resources/state-income-tax-2025-rules.csv.metadata.json`
  - `tax-content/src/main/resources/tax-config/federal-2025-pub15t.json.metadata.json`
  - `labor-service/src/main/resources/labor-standards-2025.csv.metadata.json`
- Validators:
  - `tax-service/src/main/kotlin/com/example/uspayroll/tax/tools/GeneratedTaxArtifactsValidatorCli.kt`

Acceptance criteria:
- Every curated content artifact (CSV/JSON) includes or references metadata:
  - authoritative source identifier (publication / statute)
  - year and effective date range
  - revision date
  - checksum of source material (where applicable)
  - internal approval references (SME sign-off)
- Metadata is versioned in Git and traceable to a commit SHA.

### B1 Formal approval workflow for content changes
Target priority: P0
Owner: repo process + CI

Status (as of 2025-12-16): Done
Evidence:
- CODEOWNERS:
  - `.github/CODEOWNERS`
- PR template:
  - `.github/pull_request_template.md`
- CI check entrypoint:
  - `.github/workflows/ci.yml`
- Playbooks reference the process:
  - `docs/ops/yearly-update-playbook-state-income-tax.md`
  - `docs/ops/yearly-update-playbook-labor-standards.md`

Acceptance criteria:
- Changes under `tax-content/src/main/resources` and labor standards inputs require:
  - designated reviewers (e.g., CODEOWNERS for compliance/SME)
  - a PR template section capturing source + sign-off
- CI enforces:
  - generated artifacts are up to date (CSV -> JSON -> SQL)
  - validators and golden tests pass

### B2 Yearly update playbooks expanded to state tax + labor
Target priority: P0
Owner: `tax-service`, `labor-service`

Status (as of 2025-12-16): Done
Evidence:
- `docs/ops/yearly-update-playbook-state-income-tax.md`
- `docs/ops/yearly-update-playbook-labor-standards.md`

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

### B3 Coverage expansion plan (state/local)
Target priority: P1
Owner: `tax-service`, `labor-service`

Status (as of 2025-12-16): Done
Evidence:
- `docs/ops/coverage-expansion-plan-state-local.md`

Acceptance criteria:
- Produce a state/local coverage matrix and identify:
  - supported (tax withholding, unemployment, local taxes)
  - partially supported
  - unsupported
- Include sequencing for expanding coverage.

Plan:
- `docs/ops/coverage-expansion-plan-state-local.md`

## Epic C — Enterprise payroll workflows

### C0 Off-cycle pay runs
Target priority: P0
Owner: `payroll-orchestrator-service`, `payroll-worker-service`

Status (as of 2025-12-16): Done
Evidence:
- Payrun types include `OFF_CYCLE`:
  - `payroll-orchestrator-service/src/main/kotlin/com/example/uspayroll/orchestrator/payrun/model/PayRunModels.kt`
- Workflow test:
  - `payroll-orchestrator-service/src/test/kotlin/com/example/uspayroll/orchestrator/http/OffCyclePayRunWorkflowIT.kt`

Acceptance criteria:
- Support `PayRunType.OFF_CYCLE` end-to-end:
  - create/run/approve/pay
  - ability to include supplemental earnings (bonuses/commissions)
- Clear rules for tax treatment (supplemental vs regular) and traceability in paycheck audit.
- Golden tests exist for representative off-cycle scenarios.

### C1 Corrections: void / reissue
Target priority: P0
Owner: orchestrator + payments + reporting

Status (as of 2025-12-16): Done
Evidence:
- Run types include `VOID` and `REISSUE`:
  - `payroll-orchestrator-service/src/main/kotlin/com/example/uspayroll/orchestrator/payrun/model/PayRunModels.kt`
- Accounting model doc:
  - `docs/ops/payrun-corrections-void-reissue.md`
- Workflow test:
  - `payroll-orchestrator-service/src/test/kotlin/com/example/uspayroll/orchestrator/http/PayRunVoidReissueWorkflowIT.kt`

Acceptance criteria:
- Support `PayRunType.VOID` and `PayRunType.REISSUE` semantics:
  - void reverses wages/taxes in reporting/YTD according to defined accounting model
  - reissue produces a new paycheckId with linkage to original
- Idempotent APIs and invariants documented.
- Golden workflow test covers create -> pay -> void -> reissue.

### C2 Retro pay driven by effective-dated HR changes
Target priority: P0
Owner: HR + worker + orchestrator

Status (as of 2025-12-16): Done (verify remaining scenario coverage)
Evidence:
- Retro adjustment service:
  - `payroll-orchestrator-service/src/main/kotlin/com/example/uspayroll/orchestrator/payrun/PayRunRetroAdjustmentsService.kt`
- Workflow test:
  - `payroll-orchestrator-service/src/test/kotlin/com/example/uspayroll/orchestrator/http/PayRunRetroAdjustmentWorkflowIT.kt`
- HR effective-dated + bitemporal facts exist (Epic A).

Acceptance criteria:
- When effective-dated compensation or tax-affecting attributes change in the past:
  - system can compute delta (“retro”) for impacted periods
  - system can generate adjustment run(s) deterministically
- The retro algorithm is deterministic and auditable:
  - inputs are fingerprinted (employee snapshot + tax context + labor standards)
  - replay yields identical deltas
- Golden tests include retro scenarios (rate change mid-quarter; location change; W-4 change).

### C3 Multi-jurisdiction employment scenarios
Target priority: P1
Owner: HR + tax + labor + worker

Status (as of 2025-12-16): Partial
Evidence:
- Domain-level multi-jurisdiction test:
  - `payroll-domain/src/test/kotlin/com/example/uspayroll/payroll/engine/MultiJurisdictionTaxTest.kt`
- Worker locality resolver and tests:
  - `payroll-worker-service/src/main/kotlin/com/example/uspayroll/worker/LocalityResolver.kt`
  - `payroll-worker-service/src/test/kotlin/com/example/uspayroll/worker/MichiganLocalityResolverTest.kt`
Notes:
- Multi-locality within-period allocation is modeled (`TimeSlice.localityAllocations`), but broad statutory coverage and HR modeling for multi-locality time distribution are ongoing.

Acceptance criteria:
- Support employees who:
  - live and work in different states
  - work in multiple localities within a pay period
- Explicit locality selection semantics (already partially covered in tax golden tests).

## Epic D — Payments reconciliation and downstream integration points

### D0 Payment reconciliation contract
Target priority: P0
Owner: `payments-service`, `payroll-orchestrator-service`

Status (as of 2025-12-16): Done
Evidence:
- Orchestrator internal reconciliation endpoints:
  - `payroll-orchestrator-service/src/main/kotlin/com/example/uspayroll/orchestrator/http/PayRunReconciliationController.kt`
- Projection rebuild workflow test:
  - `payroll-orchestrator-service/src/test/kotlin/com/example/uspayroll/orchestrator/http/PayRunPaymentsProjectionRebuildIT.kt`

Acceptance criteria:
- Define a stable reconciliation model:
  - payment batch identifiers
  - payment status transitions
  - linkage to paychecks/pay runs
- Provide admin/reporting endpoints to:
  - reconcile mismatches
  - rebuild projections from source of truth (already exists for some flows)

### D1 Bank file / payment provider integration seam
Target priority: P1
Owner: `payments-service`

Status (as of 2025-12-16): Done (sandbox provider)
Evidence:
- Provider port:
  - `payments-service/src/main/kotlin/com/example/uspayroll/payments/provider/PaymentProvider.kt`
- Sandbox provider:
  - `payments-service/src/main/kotlin/com/example/uspayroll/payments/provider/SandboxPaymentProvider.kt`
- Documentation:
  - `docs/ops/payments-provider-integration-seam.md`

Acceptance criteria:
- Define a port interface for payment providers (ACH/wire) and a sandbox implementation.
- Ensure sensitive data handling and encryption boundaries are documented.

## Epic E — Filings and reporting integration points

### E0 Reporting/filings event contract
Target priority: P0
Owner: `reporting-service`, `filings-service`, domain

Status (as of 2025-12-16): Done
Evidence:
- Contract doc:
  - `docs/ops/reporting-filings-paycheck-ledger-events.md`
- Messaging types:
  - `messaging-core/src/main/kotlin/com/example/uspayroll/messaging/events/reporting/PaycheckLedgerEvents.kt`
- Workflow tests:
  - `payroll-orchestrator-service/src/test/kotlin/com/example/uspayroll/orchestrator/events/PaycheckLedgerEventsIT.kt`

Acceptance criteria:
- Define events or exports that represent:
  - committed paycheck
  - voided paycheck
  - adjusted paycheck
- Include sufficient fields for filing preparation without coupling to internal tables.

### E1 Quarterly/annual filing “shape”
Target priority: P1
Owner: `filings-service`

Status (as of 2025-12-16): Done (initial shape APIs)
Evidence:
- Filings endpoints:
  - `filings-service/src/main/kotlin/com/example/uspayroll/filings/http/FilingsController.kt`
- Integration test:
  - `filings-service/src/test/kotlin/com/example/uspayroll/filings/service/FilingsComputationServiceIT.kt`
Notes:
- Production-grade filing coverage/validations will typically expand significantly by jurisdiction and customer requirements.

Acceptance criteria:
- Define the initial filing artifacts and APIs:
  - 941/940 (federal)
  - W-2/W-3
  - state withholding summaries
- Include validation hooks and reconciliation with payments.

## Cross-cutting capabilities

### X0 Golden tests coverage
Target priority: P0
Owner: cross-cutting

Status (as of 2025-12-16): Partial
Notes:
- Golden tests exist in multiple layers (domain/catalog/workflow), but full P0 coverage has not been exhaustively audited.

Acceptance criteria:
- Domain golden tests for pure calculation invariants.
- Catalog golden tests for tax/labor rule interpretation.
- Workflow golden tests for off-cycle/correction/retro + reconciliation.
