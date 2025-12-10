# Employer configuration overview

This document summarizes how employer-specific configuration currently works in the functional core and services.

## Tax overlays

See `tax-service/README.md` for details on:
- The `tax_rule` schema (`employer_id` column).
- The `TaxRuleConfig.employerId` field in JSON config.
- How to import overlay rules into the `tax_rule` table.

At runtime, `TaxQuery.employerId` and the `JooqTaxRuleRepository` selection logic ensure that:
- Generic rules (`employer_id IS NULL`) apply to all employers.
- Employer-specific rules (`employer_id = <EmployerId>`) are layered on top for that employer.

## Earning and deduction configuration

In the payroll domain (`payroll-domain`), employer-specific earnings and benefits are configured via two ports:

- `EarningConfigRepository`
  - Method: `findByEmployerAndCode(employerId: EmployerId, code: EarningCode): EarningDefinition?`
  - `EarningDefinition` captures employer-facing earning codes (e.g. `BASE`, `HOURLY`) with categories and optional default rates.
- `DeductionConfigRepository`
  - Method: `findPlansForEmployer(employerId: EmployerId): List<DeductionPlan>`
  - `DeductionPlan` describes employer-configurable deductions/benefits (401k, HSA, FSA, Roth, garnishments, etc.), including:
    - `kind` (drives tax treatment via `DeductionKind` / `DeductionEffect`).
    - `employeeRate` / `employeeFlat` and optional employer contributions.
    - Caps (`annualCap`, `perPeriodCap`).

The `DeductionsCalculator` uses these repositories to:
- Load all plans for a given employer/paycheck.
- Compute pre-tax vs post-tax deductions and their impact on tax bases.
- Emit detailed trace steps (`TraceStep.DeductionApplied`, `TraceStep.BasisComputed`) that show how each plan affected the bases.

## Example: per-employer retirement plans

The test `EmployerSpecificDeductionsGoldenTest` (payroll-domain) demonstrates how two employers with identical wages can have different tax bases and net pay due to configuration:

- Employer `emp-pretax` offers a pre-tax 401(k) (10% of gross), reducing `FederalTaxable` wages and therefore federal income tax.
- Employer `emp-roth` offers a Roth plan at the same 10%, but modeled as `ROTH_RETIREMENT_EMPLOYEE` with `NO_TAX_EFFECT`, so it does not reduce tax bases.

Both employees earn the same gross and contribute the same nominal amount to retirement, but:
- The pre-tax employer shows a lower taxable basis and lower tax withheld.
- The Roth employer shows a higher taxable basis and higher tax, with the same retirement contribution taken post-tax.

At the worker-service level, `EmployerPretaxRothWorkerIntegrationTest` provides a similar comparison using a concrete `DeductionConfigRepository` variant in the orchestration module, reinforcing that the same functional behavior holds when invoked from the service boundary.

In a future configuration service, these repositories would be backed by a database or admin UI, but the functional interfaces and tests already support realistic multi-tenant behavior today.
