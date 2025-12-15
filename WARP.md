# WARP.md

This file provides guidance to WARP (warp.dev) when working with code in this repository.

## Build, test, and run commands

The project is a Kotlin/JVM Gradle multi-module build targeting Java 21.

IMPORTANT: Ensure Gradle is run with a Java 21 runtime. If your system `java` is newer (and Kotlin tooling fails), use:
- `./scripts/gradlew-java21.sh <task>`

Use the Gradle wrapper from the repo root.

### Global Gradle tasks

- Full build (all modules + tests):
  - `./gradlew build`
- Run all tests without building artifacts:
  - `./gradlew test`
- Run tests for a single module (replace `:payroll-domain` with any included project name from `settings.gradle.kts`):
  - `./gradlew :payroll-domain:test`
- Run a single test class or test method within a module:
  - `./gradlew :payroll-domain:test --tests 'fully.qualified.TestClassName'`
  - `./gradlew :payroll-domain:test --tests 'fully.qualified.TestClassName.testMethodName'`

### Supply-chain integrity (dependency verification + locking)

This repo uses:
- Gradle dependency verification metadata at `gradle/verification-metadata.xml`
- Dependency lockfiles (`gradle.lockfile` in the repo root and per-module `*/gradle.lockfile`)

CI runs Gradle with `--locked-dependencies`, so changing/adding dependencies requires updating these files.

When you add/upgrade/remove dependencies, refresh both verification metadata and lockfiles from the repo root:
- `./scripts/gradlew-java21.sh --no-daemon --write-verification-metadata sha256 --write-locks check`

### Running Spring Boot services

Each deployable service applies the Spring Boot Gradle plugin and is runnable via `bootRun` from the repo root:

- HR service HTTP API:
  - `./gradlew :hr-service:bootRun`
- Tax service HTTP API:
  - `./gradlew :tax-service:bootRun`
- Labor service HTTP API:
  - `./gradlew :labor-service:bootRun`
- Payroll worker/orchestrator service:
  - `./gradlew :payroll-worker-service:bootRun`

Services are standard Spring Boot applications with `*ApplicationKt` main classes under `com.example.uspayroll.*`.

### Labor standards pipeline (labor-service)

Authoritative labor standards data is maintained as a CSV and compiled into JSON + SQL artifacts.

From the repo root:

- Run labor-service tests and regenerate JSON + SQL artifacts for a given labor year (default 2025):
  - Direct Gradle tasks:
    - `./gradlew :labor-service:test --no-daemon`
    - `./gradlew :labor-service:runLaborStandardsImporter --no-daemon -PlaborYear=2025`
  - Or via the helper script (uses `LABOR_YEAR`, default 2025):
    - `LABOR_YEAR=2025 ./scripts/refresh-labor-standards.sh`

The importer reads `labor-service/src/main/resources/labor-standards-<YEAR>.csv` and regenerates:

- `labor-standards-<YEAR>.json` (runtime config)
- `labor-standard-<YEAR>.sql` (INSERTs for the `labor_standard` table)

### State income tax pipeline (tax-service)

State income tax content is maintained in CSVs and compiled into JSON config, then imported into the `tax_rule` table.

From the repo root:

- Regenerate state income tax JSON for a given tax year (default 2025):
  - `./gradlew :tax-service:test --no-daemon`
  - `./gradlew :tax-service:runStateIncomeTaxImporter --no-daemon -PtaxYear=2025`
- Validate all tax rule JSON config files under `tax-content/src/main/resources/tax-config`:
  - `./gradlew :tax-service:validateTaxConfig --no-daemon`

The importer and validator operate on `TaxRuleFile` JSON documents generated from:

- `state-income-tax-<YEAR>-rules.csv`
- `state-income-tax-<YEAR>-brackets.csv`

## High-level architecture

The system is built around a functional payroll core surrounded by service modules and infrastructure.

### Core modules

- `shared-kernel`:
  - Shared value types and identifiers such as `EmployerId`, `EmployeeId`, `Money`.
  - No dependency on Spring, HTTP, or databases.
- `payroll-domain`:
  - Pure calculation logic and strongly-typed models for payroll.
  - Organized into:
    - `com.example.uspayroll.payroll.model.*` – domain models (`EmployeeSnapshot`, `PayPeriod`, `EarningLine`, `TaxLine`, `DeductionLine`, `PaycheckInput`, `PaycheckResult`, `TaxContext`, `TaxRule`, `TaxBasis`, `TaxJurisdiction`, `YtdSnapshot`, `CalculationTrace`, etc.).
    - `com.example.uspayroll.payroll.model.config.*` – configuration models and ports (`EarningDefinition`, `DeductionPlan`, `EarningConfigRepository`, `DeductionConfigRepository`).
    - `com.example.uspayroll.payroll.engine.*` – orchestration and calculators (`PayrollEngine`, `EarningsCalculator`, `TaxesCalculator`, `DeductionsCalculator`, `YtdAccumulator`).
  - The domain is intentionally free of framework and transport concerns and depends only on port-style interfaces.

### Domain layout rules

When modifying or extending the payroll core, keep these constraints in mind:

- New domain models (entities, value objects, DTOs, configuration types) belong under `com.example.uspayroll.payroll.model.*`.
- New calculation or orchestration logic belongs under `com.example.uspayroll.payroll.engine.*`.
- Avoid adding new top-level Kotlin files directly under `payroll-domain/src/main/kotlin` in the default package; keep files small and focused by concern.
- Preserve the functional-core style: business logic should be expressed as pure functions over immutable value types; side effects and I/O live at service boundaries.

### Service boundaries

The runtime topology is a multi-service Spring Boot deployment, with each bounded context exposed via its own HTTP API and sharing the payroll domain core.

- **HR service (`hr-service`)**
  - Owns employee and pay period data and related schema.
  - Implements ports such as `EmployeeSnapshotProvider` and `PayPeriodProvider` that return snapshots and pay periods.
  - Exposes HTTP endpoints under `/employers/{employerId}` for employee snapshots and pay periods.
- **Tax service (`tax-service`)**
  - Owns tax rules/statutory data and the `tax_rule` catalog schema.
  - Implements `TaxContextProvider` to assemble `TaxContext` for a given employer/as-of date.
  - Exposes HTTP endpoints under `/employers/{employerId}` for obtaining tax context and, in future, managing tax configuration workflows.
- **Labor service (`labor-service`)**
  - Owns labor standards data (minimum wage, tipped wage, overtime thresholds, etc.).
  - Implements a `LaborStandardsContextProvider` abstraction and HTTP endpoints to return effective labor standards for an employer/date/state.
- **Payroll worker service (`payroll-worker-service`)**
  - Orchestrates pay runs by coordinating HR, Tax, and Labor services and calling into `PayrollEngine`.
  - Defines HTTP endpoints such as:
    - `POST /employers/{employerId}/payruns` – run payroll for multiple employees/periods.
    - `POST /employers/{employerId}/paychecks/preview` – preview a single paycheck.
  - Uses typed clients (`HrClient`, `TaxClient`, `LaborStandardsClient`) that in turn call the service HTTP APIs.

Other modules such as `persistence-core`, `messaging-core`, and `web-core` exist to factor out shared infrastructure concerns; when adding new cross-cutting integration code, prefer extending these modules over duplicating plumbing in individual services.

### Separation of concerns

- The **domain core** (`shared-kernel` + `payroll-domain`) must stay framework-agnostic and side-effect free.
- Service modules encapsulate persistence, HTTP, and operational concerns while depending on the domain only via its public types and ports.
- Configuration-heavy content (tax rules, labor standards) lives as versioned CSV/JSON artifacts in `tax-content` (tax) and the relevant service modules (e.g. labor) and is compiled/imported via explicit Gradle tasks.

### Year and coverage assumptions

- Current rules and standards are primarily modeled for the 2025 tax and labor year.
- When generating new tax or labor content, prefer to follow the existing 2025-focused pipelines unless explicitly expanding year coverage.

This architecture is intended to keep payroll calculations deterministic and testable while allowing HR, Tax, Labor, and configuration pipelines to evolve independently in their own modules and services.