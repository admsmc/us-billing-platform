# US Payroll Platform Architecture

This document summarizes the high-level architecture and key boundaries in the system.

## Modules

## Build and analysis toolchain

The repo standardizes on a single Kotlin/Gradle/static analysis stack and enforces it via
Gradle dependency verification + strict dependency locking:

- Gradle wrapper: **8.12.1** (see `gradle/wrapper/gradle-wrapper.properties`).
- Kotlin Gradle plugins: **2.0.21** (configured in the root `build.gradle.kts`).
- Detekt Gradle plugin: **1.23.8**.
- ktlint Gradle plugin: **14.0.1**.

Repository-wide rules:

- All Kotlin modules must use the root-configured Kotlin plugin versions; do not override
  Kotlin versions in submodules.
- Detekt must run against the same Kotlin toolchain as the build. If you upgrade Kotlin or
  Detekt, you must:
  - Update versions in the root `build.gradle.kts` plugin block.
  - Refresh dependency verification metadata and all lockfiles:
    - `./scripts/gradlew-java21.sh --no-daemon --write-verification-metadata sha256 --write-locks check`
    - `./scripts/gradlew-java21.sh --no-daemon -PenableDetekt=true --write-locks resolveAndLockAll`
- Dependency locking is configured in **STRICT** mode for all modules. When Detekt or ktlint
  dependencies change (including transitive Kotlin compiler artifacts), you must regenerate
  lock state; otherwise builds will fail with locking errors.
- Manual edits to `gradle.lockfile` files should be rare and surgical; they are acceptable
  only to remove stale entries for configurations that are immediately re-locked by running
  Gradle with `--write-locks`.

These constraints are part of the architecture: they ensure static analysis runs with a
consistent, enterprise-grade toolchain that matches the main build and is auditable via
verification metadata and lockfiles.

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

The HR service is responsible for employee and pay period data. The service-side ports live in `hr-api`, e.g. `hr-api/src/main/kotlin/com/example/uspayroll/hr/api/HrPorts.kt`:

- `EmployeeSnapshotProvider`:
  - `getEmployeeSnapshot(employerId, employeeId, asOfDate): EmployeeSnapshot?`
- `PayPeriodProvider`:
  - `getPayPeriod(employerId, payPeriodId): PayPeriod?`
  - `findPayPeriodByCheckDate(employerId, checkDate): PayPeriod?`

These interfaces are implemented by `hr-service` using its persistence layer and are exposed via HTTP.

Representative HTTP surfaces (implemented under `com.example.uspayroll.hr.http`):
- Read routes: `hr-service/src/main/kotlin/com/example/uspayroll/hr/http/HrRoutes.kt`
- Write + effective-dated routes: `hr-service/src/main/kotlin/com/example/uspayroll/hr/http/HrWriteController.kt`
- Pay schedule upsert + pay period generation: `hr-service/src/main/kotlin/com/example/uspayroll/hr/http/HrPayScheduleWriteController.kt`

The worker/orchestrator primarily consume HR via typed HTTP clients (e.g., `hr-client` / `HrClient`). For tests, in-memory implementations can call the underlying ports directly.

## Tax service boundary (`tax-service`)

The tax service is responsible for assembling `TaxContext` from statutory rules and employer-specific tax configuration. The primary port lives in `tax-api`, e.g. `tax-api/src/main/kotlin/com/example/uspayroll/tax/api/TaxContextProvider.kt`:

- `TaxContextProvider`:
  - `getTaxContext(employerId, asOfDate): TaxContext`

This interface is implemented by `tax-service` using tax tables, effective-dated rules, and employer-level settings. The worker/orchestrator call it via a client abstraction.

The tax service exposes HTTP routes (implemented) under `com.example.uspayroll.tax.http`, e.g.:

- `tax-service/src/main/kotlin/com/example/uspayroll/tax/http/TaxRoutes.kt`
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
- **Worker service**: wires everything together at runtime by providing config repositories and HR/Tax clients that call external services.

This structure is intended to keep payroll logic deterministic and testable while allowing HR, tax, and config concerns to evolve independently in their own services.