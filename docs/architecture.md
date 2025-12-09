# US Payroll Platform Architecture

This document summarizes the high-level architecture and key boundaries in the system.

## Modules

- `shared-kernel`: common value types such as `EmployerId`, `EmployeeId`, `Money`.
- `payroll-domain`: pure domain logic and types for payroll calculations.
- `payroll-worker-service`: service responsible for running payroll calculations against the domain.
- `hr-service`: owns employee and pay period data and exposes them via service interfaces.
- `tax-service`: owns tax rules/statutory data and exposes them via service interfaces.

## Domain core (`payroll-domain`)

The payroll domain is designed as a functional core with the following key types and layout rules:

- Models in `payroll.model.*`:
  - `EmployeeSnapshot`, `PayPeriod`, `TimeSlice`, `EarningLine`, `TaxLine`, `DeductionLine`, `PaycheckInput`, `PaycheckResult`.
  - `TaxContext`, `TaxRule`, `TaxBasis`, `TaxJurisdiction`.
  - `YtdSnapshot` for year-to-date accumulations.
  - `CalculationTrace` and `TraceStep` for explainability.
- Config-related models in `payroll.model.config`:
  - `EarningDefinition`, `EarningConfigRepository`.
  - `DeductionKind`, `DeductionPlan`, `DeductionConfigRepository`.
- Engine components in `payroll.engine`:
  - `PayrollEngine`: orchestrates a single paycheck calculation.
  - `EarningsCalculator`: computes earnings lines.
  - `TaxesCalculator`: applies tax rules to produce employee/employer tax lines.
  - `DeductionsCalculator`: applies configured deductions/benefits.
  - `YtdAccumulator`: updates `YtdSnapshot` based on the results.

### Domain layout and "no god file" rule

To keep the domain modular and avoid god files:

- **All new domain model types** (entities, value objects, DTOs, etc.) **must live under**
  `com.example.uspayroll.payroll.model.*` (e.g. `payroll-domain/src/main/kotlin/com/example/uspayroll/payroll/model/...`).
- **All new engine logic** (orchestration, calculators, helpers) **must live under**
  `com.example.uspayroll.payroll.engine.*`.
- **Do NOT add new top-level Kotlin files** directly under `payroll-domain/src/main/kotlin/*.kt`
  in the default package. Those are considered legacy and have been removed.
- Prefer small, focused files grouped by concern (e.g. `TaxTypes.kt`, `YtdTypes.kt`,
  `DeductionsCalculator.kt`) rather than reintroducing a single monolithic `Domain.kt`.

The domain does not depend on HTTP, databases, or specific frameworks. It only depends on port interfaces such as `EarningConfigRepository` and `DeductionConfigRepository`.

## HR service boundary (`hr-service`)

The HR service is responsible for employee and pay period data. It defines service-side ports in `hr-service/src/main/kotlin/HrPorts.kt`:

- `EmployeeSnapshotProvider`:
  - `getEmployeeSnapshot(employerId, employeeId, asOfDate): EmployeeSnapshot?`
- `PayPeriodProvider`:
  - `getPayPeriod(employerId, payPeriodId): PayPeriod?`
  - `findPayPeriodByCheckDate(employerId, checkDate): PayPeriod?`

These interfaces will be implemented by `hr-service` using its own persistence layer (e.g., a database). Other components (orchestrator/worker) will consume these via client abstractions.

The `hr.http` package currently only contains a placeholder noting the intended REST route shapes.

## Tax service boundary (`tax-service`)

The tax service is responsible for assembling `TaxContext` from statutory rules and employer-specific tax configuration. It defines a port in `tax-service/src/main/kotlin/TaxPorts.kt`:

- `TaxContextProvider`:
  - `getTaxContext(employerId, asOfDate): TaxContext`

This interface will be implemented by `tax-service` using tax tables, effective-dated rules, and employer-level settings. The worker/orchestrator will call it via a client abstraction.

The `tax.http` package currently documents the intended REST endpoint:

- `GET /employers/{employerId}/tax-context?asOf=YYYY-MM-DD`

## Worker-side clients (`payroll-worker-service`)

The worker service uses client interfaces to talk to HR and Tax services, and config repositories for employer-specific configuration:

- HR client in `payroll-worker-service/src/main/kotlin/com/example/uspayroll/worker/client/HrClient.kt`:
  - `getEmployeeSnapshot(employerId, employeeId, asOfDate): EmployeeSnapshot?`
  - `getPayPeriod(employerId, payPeriodId): PayPeriod?`
  - `findPayPeriodByCheckDate(employerId, checkDate): PayPeriod?`
- Tax client in `payroll-worker-service/src/main/kotlin/com/example/uspayroll/worker/client/TaxClient.kt`:
  - `getTaxContext(employerId, asOfDate): TaxContext`
- Config repositories:
  - `InMemoryEarningConfigRepository`: implements `EarningConfigRepository` with hardcoded definitions for now.
  - `InMemoryDeductionConfigRepository`: implements `DeductionConfigRepository` with a simple voluntary post-tax plan.

`PayrollEngine.calculatePaycheck` accepts optional `EarningConfigRepository` and `DeductionConfigRepository` parameters so the worker can provide configuration without coupling the domain to any particular service.

## Separation of concerns summary

- **Domain core**: pure calculation logic and strongly-typed models. No HTTP/DB logic.
- **HR service**: owns employee and pay period data. Exposes `EmployeeSnapshotProvider` and `PayPeriodProvider`.
- **Tax service**: owns tax rules and statutory logic. Exposes `TaxContextProvider`.
- **Config boundary**: earnings and deductions are configured via `EarningDefinition`/`DeductionPlan` and accessed through `EarningConfigRepository`/`DeductionConfigRepository`.
- **Worker service**: wires everything together at runtime by providing config repositories and, in the future, HR/Tax clients that call external services.

This structure is intended to keep payroll logic deterministic and testable while allowing HR, tax, and config concerns to evolve independently in their own services.