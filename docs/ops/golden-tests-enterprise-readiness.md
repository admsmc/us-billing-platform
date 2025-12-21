# Golden tests for enterprise readiness

This document defines how we use “golden tests” to lock in payroll correctness and enterprise workflows.

A golden test is:
- deterministic (no wall clock, random IDs, or external dependencies)
- replayable (same inputs -> same outputs)
- stable (inputs are versioned; outputs are asserted explicitly)

## Design principles

1. **Determinism over mocking**
Prefer deterministic fixtures and in-memory/in-process integrations over heavy mocking.

2. **Versioned test vectors**
Golden fixtures must be versioned and traceable:
- HR snapshots should have effective dates and stable IDs.
- Tax/Labor configs should reference the exact JSON/CSV artifacts and year.

3. **Two-layer outputs**
For enterprise auditability, golden tests should assert both:
- paycheck numeric outputs (tax lines, deduction lines, net)
- audit outputs (e.g., `PaycheckAudit` fingerprints and applied rule IDs)

4. **Boundary testing**
Golden tests should focus on statutory edge cases (thresholds, caps, bracket boundaries) and workflow boundaries (void/reissue/retro ordering).

## Golden test layers

### Layer 1 — Domain golden tests (`payroll-domain`)
Goal: prove the payroll core is correct and stable independent of persistence/HTTP.

Examples:
- FICA wage base cap and Additional Medicare thresholds across year-to-date.
- Supplemental wage taxation rules (flat rate vs aggregate).
- Garnishment limits and priority ordering.
- Overtime and regular rate computations under FLSA.

Acceptance criteria:
- Pure test harness: no DB, no HTTP.
- Uses immutable domain inputs with explicit `computedAt`.

### Layer 2 — Catalog golden tests (Tax/Labor)
Goal: prove rule ingestion and rule selection semantics are correct.

Existing examples in this repo:
- H2-backed golden tests in `tax-service` that import JSON -> DB -> catalog -> context -> TaxesCalculator.

Acceptance criteria:
- Uses deterministic import paths (resources in repo).
- Tests include “structure tests” (rule presence + invariants) and “value tests” (exact cents).

### Layer 3 — Workflow golden tests (services)
Goal: prove real enterprise workflows across service boundaries.

Targets:
- `payroll-worker-service` and `payroll-orchestrator-service` for pay run lifecycles.
- `payments-service` for payment status and reconciliation.
- `reporting-service` / `filings-service` for downstream event contracts.

Acceptance criteria:
- Uses in-process mode where possible; otherwise uses embedded DB/brokers with strict timeouts.
- Every workflow test asserts:
  - state transitions
  - persisted audit records
  - idempotency invariants

## Initial golden test catalog (prioritized)

### P0 statutory golden tests
1. **Federal FIT (Pub 15-T)**
- Wage bracket exact values at representative boundary wages for each filing status and Step2 variant.

2. **FICA / Additional Medicare**
- Multiple paychecks across year boundary and across wage caps.

3. **State/local selection**
- Locality selection semantics for at least one complex state (MI already has tests).

4. **Labor standards application**
- Minimum wage enforcement + tipped wage + tip credit policies for at least one state with special rules.

### P0 workflow golden tests
1. **Off-cycle run**
- Regular run establishes YTD; off-cycle bonus run applies correct tax treatment and audit.

2. **Void / reissue**
- Pay a run, void it, reissue it; assert reporting/payments linkage and YTD adjustments.

3. **Retro pay**
- Effective-dated compensation change backdated; retro adjustment run computes correct delta and is replayable.

4. **Payment reconciliation**
- Simulate payment batch partial failure; assert pay run payment status transitions and reconciliation endpoint correctness.

## Fixture conventions

### HR fixtures
- Prefer small canonical employee fixtures with explicit effective date ranges.
- Use stable IDs and deterministic dates.

### Tax/Labor fixtures
- Use the repo’s versioned artifacts in `tax-content` and labor resources.
- Any new year must include:
  - CSV (curated)
  - generated JSON
  - golden tests updated to that year’s values

## Release gates (minimum)
For tagged releases and production promotion candidates, require (at minimum):

1) Full repo checks:
- `./scripts/gradlew-java21.sh --no-daemon check`

2) Canary golden suites (explicit, stable, high-signal)
Run these as explicit gates (even though `check` already runs tests) so release evidence is easy to audit:
- Domain canaries (`payroll-domain`):
  - `com.example.uspayroll.payroll.engine.OffCyclePaycheckGoldenTest`
  - `com.example.uspayroll.payroll.engine.EmployerSpecificDeductionsGoldenTest`
- Tax catalog canaries (`tax-service`):
  - `com.example.uspayroll.tax.persistence.Pub15TFederalGoldenTest`
  - `com.example.uspayroll.tax.persistence.FicaAndAdditionalMedicareDbGoldenTest`
  - `com.example.uspayroll.tax.persistence.MichiganLocalTaxGoldenTest`
- Workflow canaries (`payroll-orchestrator-service`):
  - `com.example.uspayroll.orchestrator.http.OffCyclePayRunWorkflowIT`
  - `com.example.uspayroll.orchestrator.http.PayRunVoidReissueWorkflowIT`

Example invocation pattern:
- `./scripts/gradlew-java21.sh --no-daemon :payroll-domain:test --tests 'com.example.uspayroll.payroll.engine.OffCyclePaycheckGoldenTest'`

These canaries should expand over time, but should remain small enough to run quickly and catch regressions in the most critical statutory and workflow paths.

## When golden tests must be updated
Golden tests should change only when:
- statutory rules change (new year / rule revision)
- a bug fix corrects previously wrong behavior

In both cases:
- include a short explanation in the PR
- reference the authoritative source (publication / statute / ticket)
- ensure traceability from source -> curated inputs -> generated artifacts -> tests
