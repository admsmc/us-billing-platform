# Payroll system feature review (repository vs typical expectations)
Date: 2025-12-16
Scope: repository-derived review of `us-payroll-platform`

## 1. How to read this document
This is a practical “gap and strengths” review of the repository against what a production payroll system is typically expected to provide.

For each area, you’ll see:
- What a payroll system is typically expected to support
- What this repository appears to implement today (based on code + docs)
- Notes / gaps / maturity signals

This is not a compliance certification and does not assert statutory completeness.

## 2. High-level: where this repository is strong
This repository is unusually strong (for its current maturity stage) in the engineering and ops primitives that are normally hard to retrofit later:
- Functional-core payroll computation (`payroll-domain`) separated from service concerns.
- Deterministic, replay-safe workflow semantics (Idempotency-Key, outbox/inbox, DLQ/reconciliation) – see `docs/ops/idempotency-and-replay-invariants.md` and `docs/ops/dlq-replay-reconciliation.md`.
- Content governance pipelines for tax and labor data (CSV → JSON → DB/SQL) with yearly-update playbooks.
- Multi-tenant isolation target of DB-per-employer with migration and ops scaffolding – see `docs/tenancy-db-per-employer.md`.
- Production posture scaffolding: hardened K8s baseline, supply-chain scanning, SBOM generation.

## 3. Feature-by-feature review
### 3.1 Employer / tenant model
Expected:
- Clear tenant boundary and isolation strategy
- Deterministic tenant routing in services
- Tenant-level export/retention/delete and DR readiness

In this repository:
- DB-per-employer tenant isolation is an explicit target (`docs/tenancy-db-per-employer.md`).
- Tenant routing is implemented via a routing datasource (`tenancy-core/src/main/kotlin/com/example/uspayroll/tenancy/db/TenantRoutingDataSource.kt`).
- Retention/export/delete posture is documented with scaffolding (`docs/ops/retention-export-delete.md`).
- DR/restore playbook exists (`docs/ops/backup-restore-dr.md`).

Notes / gaps:
- DB-per-employer is operationally heavy; the repository calls out the need for automation (provisioning, secrets, migrations).

### 3.2 HR system-of-record (employee facts)
Expected:
- Employee profile + work/home jurisdiction (for tax/labor)
- Effective-dated history and audit trail
- Deterministic “as-of” reads (for recompute and audits)

In this repository:
- Employee snapshot model is rich and payroll-relevant (`payroll-domain/.../EmployeeTypes.kt`).
- HR service exposes snapshot reads via HTTP (`hr-service/.../HrRoutes.kt`).
- Bitemporal + audit semantics are explicitly designed and documented (`docs/ops/hr-bitemporal-and-audit-semantics.md`).

Notes / gaps:
- The enterprise-readiness capability register now marks core HR write + effective dating + audit semantics as **Done** (A0–A3). Remaining work is primarily around breadth (additional HR attributes and validations), operator/admin workflows, and long-horizon backtesting.

### 3.3 Pay schedules and pay periods
Expected:
- Pay schedules (weekly/biweekly/semi-monthly/etc.)
- Pay period generation, validation, and stable identifiers
- Deterministic lookup by check date

In this repository:
- Pay frequencies are modeled (`payroll-domain/.../TimeTypes.kt`).
- HR service exposes pay period reads including by check date (`hr-service/.../HrRoutes.kt`).
- Pay-period validation rules and gap-free options are documented (`docs/ops/hr-pay-period-management.md`).

Notes / gaps:
- Pay schedule upsert + pay period generation/validation is present and marked **Done** (A2) in the enterprise-readiness capability register. Remaining work is typically around admin UX, more schedule variants, and operational controls.

### 3.4 Paycheck computation engine (functional core)
Expected:
- Deterministic paycheck calculation with explainability
- Support for common pay bases: salaried and hourly
- Handling of supplemental earnings
- Overtime and basic labor-rule hooks

In this repository:
- Salaried and hourly base compensation are first-class (`BaseCompensation` in `payroll-domain/.../EmployeeTypes.kt`).
- Per-period time/earnings inputs are modeled via `TimeSlice` with:
  - regular and overtime hours
  - additional earning inputs
  - ability to suppress base earnings for off-cycle runs (`includeBaseEarnings=false`) (`payroll-domain/.../TimeTypes.kt`).
- Overtime behavior is policy-driven (`payroll-domain/.../OvertimePolicy.kt`).
- Explainability exists via trace types and trace levels (see `payroll-domain/.../TraceTypes.kt` and `payroll-domain/.../audit/TraceLevel.kt`).

Notes / gaps:
- “Full-time / part-time / shift” aren’t explicit domain enums; they’re represented via salary vs hourly + hours/proration + earning lines.
- More complex time rules (multiple rates, blended overtime, union rules, job costing) are not indicated as complete.

### 3.5 Earnings catalog and earnings types
Expected:
- Employer-defined earning codes mapped to semantic categories (regular, OT, bonus, etc.)
- Support for “supplemental” earnings and different tax treatments

In this repository:
- System-level earning categories exist (`EarningCategory` in `payroll-domain/.../CommonTypes.kt`).
- The engine supports arbitrary additional earning lines per period (`TimeSlice.otherEarnings`).
- Employer-specific earning definitions are abstracted behind a repository port (see `docs/employer-config.md`).

Notes / gaps:
- A full “earning configuration service/UI” is not present; current wiring uses in-memory repositories in services.

### 3.6 Deductions and employer contributions (benefits)
Expected:
- Pre-tax and post-tax deductions (401k, HSA/FSA, etc.)
- Employer contributions
- Caps and per-period limits

In this repository:
- Deduction configuration is expressed via ports and domain models (`docs/employer-config.md`).
- Golden tests exist for employer-specific deduction differences (referenced in `docs/employer-config.md`).

Notes / gaps:
- A production-grade benefits enrollment/config system and persistence is not shown.

### 3.7 Taxes: rule modeling, catalogs, and withholding
Expected:
- Federal income tax withholding
- FICA (SS/Medicare + Additional Medicare)
- State income tax withholding
- Local income taxes in select jurisdictions
- Employer taxes (FUTA/SUTA/SDI) depending on scope
- Yearly update workflow for statutory changes

In this repository:
- Tax rules are modeled as data (TaxRule/TaxContext) and served via `tax-service` (`tax-service/.../TaxRoutes.kt`).
- Tax content workflows exist:
  - Federal Pub 15-T wage bracket pipeline (see `docs/tax-content-operations.md`).
  - State income tax pipeline (CSV → JSON → DB) documented in `tax-service/README.md`.
- Employer-specific overlays are supported via `tax_rule.employer_id` and layered selection (`docs/employer-config.md`).
- Tests exist for federal withholding and for local/jurisdiction selection semantics (multiple golden tests under `tax-service/src/test`).

Notes / gaps:
- The repository README explicitly says coverage breadth and multi-year support are not complete (`README.md`).
- A payroll system typically needs broader tax surfaces (SUI/SDI, reciprocal agreements, local edge cases); this repository has the scaffolding and some coverage but is not presented as nationwide-complete.

### 3.8 Labor standards
Expected:
- Minimum wage (federal + state/local)
- Overtime thresholds and special rules
- Tipped wage / tip credit rules where applicable

In this repository:
- Labor standards are provided as a service boundary (`labor-service`) and maintained as CSV with deterministic JSON + SQL regeneration (`labor-service/README.md`).
- The domain has a tip-credit “make-up” enforcer for tipped employees given a labor standards context (`payroll-domain/.../TipCreditEnforcer.kt`).

Notes / gaps:
- Labor standards coverage is described as evolving; state/local complexity will require continued expansion.

### 3.9 Garnishments (including support)
Expected:
- Garnishment orders and withholding calculation
- Protected earnings floors, state-specific rules, and support caps
- Operational reconciliation and error handling

In this repository:
- Garnishment modeling and a substantial calculator + test suite exist in the domain (`payroll-domain/.../GarnishmentsCalculator.kt` and tests).
- HR service owns garnishment orders and a ledger, including migrations and validation (`hr-service/.../garnishment/*` and `hr-service/src/main/resources/db/migration/hr/*garnishment*`).
- Worker service records withholding events back to HR; replay tooling and metrics exist (`payroll-worker-service/README.md`).

Notes / gaps:
- A fully “all states, all order types” garnishment product is typically extensive; this repository contains meaningful groundwork plus ops hooks.

### 3.10 Payruns (batch payroll execution)
Expected:
- Run payroll for many employees for a period
- Scale-out execution model
- Idempotent APIs and safe retries
- Clear “status” views and operator controls

In this repository:
- Orchestrator exposes payrun lifecycle endpoints with idempotency keys (`payroll-orchestrator-service/.../PayRunController.kt`).
- Execution is designed as queue-driven per-employee jobs (RabbitMQ) with DLQ and reconciliation workflows (see `docs/ops/payrun-jobs-observability.md` and `docs/ops/dlq-replay-reconciliation.md`).
- Worker has job controllers and internal replay mechanisms (guarded by internal auth).

Notes / gaps:
- This is one of the most production-oriented parts of the repository; the remaining gaps are more about “business breadth” and governance, not core mechanics.

### 3.11 Corrections: void, reissue, retro
Expected:
- Ability to void and reissue paychecks
- Retroactive adjustments when HR facts change
- Clear ledger semantics for downstream reporting

In this repository:
- VOID and REISSUE are implemented and documented (`docs/ops/payrun-corrections-void-reissue.md`).
- Retro adjustment workflows exist in orchestrator (tests indicate a workflow exists, e.g. `PayRunRetroAdjustmentWorkflowIT`).
- Ledger semantics are formalized for downstream consumers (`docs/ops/reporting-filings-paycheck-ledger-events.md`).

Notes / gaps:
- Corrections are currently modeled as deterministic clones/negations rather than re-computing from potentially changed historical inputs (explicitly called out as a limitation).

### 3.12 Payments / money movement
Expected:
- Payment initiation per paycheck
- Provider integration (ACH/check/wire) with safe handling of banking PII
- Reconciliation and lifecycle tracking

In this repository:
- Payments-service exists as system-of-record with a provider integration seam (`payments-service/.../provider/PaymentProvider.kt`).
- The seam explicitly avoids raw account/routing numbers; docs call for tokenization (`docs/ops/payments-provider-integration-seam.md`).
- Orchestrator can rebuild projections from payments-service (internal reconciliation endpoints).

Notes / gaps:
- A real ACH/direct-deposit provider integration is not implemented here; the repository provides the seam and sandbox-like behaviors.

### 3.13 Reporting and filings
Expected:
- Stable payroll ledger for reporting and compliance
- Filing “shapes” (941/940/W-2/W-3, state summaries)
- Deterministic recomputation / audit support

In this repository:
- A stable paycheck ledger event contract is defined for downstream consumers (`docs/ops/reporting-filings-paycheck-ledger-events.md`).
- Filings-service exposes endpoints for 941/940/W-2/W-3 and state withholding summaries (`filings-service/.../FilingsController.kt`).

Notes / gaps:
- Filing generation in practice requires deep validation, per-jurisdiction rules, and reconciliation with payments; this repository has early structure and integration seams.

### 3.14 Security and identity
Expected:
- Authentication and authorization at ingress
- Tenant scoping enforcement
- Privileged operation controls and audit logs

In this repository:
- Edge service enforces JWT-based authorization and employer scoping (`edge-service/.../EdgeAuthorizationFilter.kt`).
- Internal privileged endpoints are guarded via internal auth (shared secret/JWT scaffolding) and security audit logging (`docs/security-boundary.md`, `docs/security-threat-model.md`).

Notes / gaps:
- `identity-service` module exists but is currently empty; production deployments need an IdP integration and hardened policies.

### 3.15 Observability and ops readiness
Expected:
- Metrics, logs, traces for core workflows
- Operator runbooks, SLOs, alerting

In this repository:
- SLO guidance and PromQL examples exist (`docs/ops/slo-core-workflows.md`).
- Payrun job observability is documented (`docs/ops/payrun-jobs-observability.md`).
- Trace propagation concepts are documented and OTLP config exists in service configs.

Notes / gaps:
- Production-grade dashboards/alerts and organization-specific on-call runbooks typically need tailoring, but the repository has strong starting templates.

### 3.16 Supply-chain and build quality
Expected:
- Dependency hygiene, vulnerability scanning, SBOMs

In this repository:
- Dependency locking + verification metadata and CI checks.
- OWASP Dependency-Check, Trivy, TruffleHog, CodeQL workflows exist under `.github/workflows/`.

## 4. Summary: what the repository “already is” vs “what remains”
Already present (strong foundation)
- Functional payroll engine with traceability
- Tax/labor content pipelines and year-update posture
- Garnishment modeling and ops hooks
- Queue-driven payrun execution with replay-safe primitives
- Payment integration seam with strong security stance on bank PII
- Filing/reporting contracts and early filing endpoints
- Security boundary plan + gateway enforcement model
- Multi-tenant DB-per-employer target + ops runbooks

Likely remaining work for a production payroll system
- Broader statutory coverage (state/local edge cases; multi-year backtesting)
- HR admin workflows and breadth hardening (more attributes, validations, and enterprise-grade audit/PII controls)
- Real payment provider integrations and settlement/returns pipelines
- Deeper filings compliance logic, validations, and reconciliation workflows
- Tenancy provisioning automation and hardened secrets management integration
- End-to-end identity/authorization integration across all services

A companion “prioritized gap list” aligned to a first enterprise go-live (P0/P1) can be produced by mapping this review to the enterprise-readiness capability register at `docs/ops/enterprise-readiness-capability-backlog.md`.
