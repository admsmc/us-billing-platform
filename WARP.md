# WARP.md

This file provides guidance to WARP (warp.dev) when working with code in this repository.

## Build, test, and run commands

## Documentation / repo hygiene

Generated artifacts
- Anything under `**/build/**` (including `**/build/reports/**`) is generated output (e.g., Detekt reports).
- These files are already ignored by `.gitignore` (`**/build/`) and should not be treated as source docs during documentation reviews/sweeps.

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

Each deployable service applies the Spring Boot Gradle plugin and is runnable via `bootRun` from the repo root.

**Billing platform services:**
- Customer service HTTP API:
  - `./gradlew :customer-service:bootRun`
- Rate service HTTP API:
  - `./gradlew :rate-service:bootRun`
- Regulatory service HTTP API:
  - `./gradlew :regulatory-service:bootRun`
- Billing worker service:
  - `./gradlew :billing-worker-service:bootRun`
- Billing orchestrator service:
  - `./gradlew :billing-orchestrator-service:bootRun`

**Legacy payroll services (deprecated):**
- HR service HTTP API:
  - `./gradlew :hr-service:bootRun`
- Tax service HTTP API:
  - `./gradlew :tax-service:bootRun`
- Labor service HTTP API:
  - `./gradlew :labor-service:bootRun`
- Payroll worker service:
  - `./gradlew :payroll-worker-service:bootRun`
- Payroll orchestrator service:
  - `./gradlew :payroll-orchestrator-service:bootRun`

Services are standard Spring Boot applications with `*ApplicationKt` main classes under `com.example.usbilling.*` (billing) or `com.example.uspayroll.*` (payroll).

### Configuration approach

Services use Spring Boot profiles for environment-specific configuration:

- Default profile: Local development with sensible defaults
- `benchmark` profile: Queue-driven benchmarks with internal JWT authentication configured
- Profile-specific YAML files: `application-<profile>.yml` in each service's `src/main/resources/`

**Important**: Spring Boot Map properties (e.g., `orchestrator.internal-auth.jwt-keys`) do NOT bind reliably from environment variables with underscore or dot notation. Use one of these approaches:

1. **Profile-based (recommended for dev/test)**: Create `application-<profile>.yml` with nested YAML:
   ```yaml
   orchestrator:
     internal-auth:
       jwt-keys:
         k1: dev-secret
       jwt-default-kid: k1
   ```
   Activate via `SPRING_PROFILES_ACTIVE=<profile>`.

2. **SPRING_APPLICATION_JSON (docker-compose overlays)**: For environment-specific overrides:
   ```yaml
   environment:
     SPRING_APPLICATION_JSON: |
       {"orchestrator": {"internal-auth": {"jwt-keys": {"k1": "secret"}, "jwt-default-kid": "k1"}}}
   ```

3. **ConfigMap + Secret mounting (Kubernetes)**: Mount application YAML as ConfigMap and reference Secrets via placeholders.

See `docs/ops/secrets-and-configuration.md` for detailed guidance.

### Benchmarking

Queue-driven benchmarks simulate realistic payrun workloads. See `benchmarks/README.md` for:

- Running benchmarks via `docker-compose.bench-parallel.yml` (uses `benchmark` profile)
- Seeding test data with `benchmarks/seed/seed-benchmark-data.sh`
- Interpreting results (throughput, latency, queue metrics)

Example:
```bash
docker compose -f docker-compose.yml -f docker-compose.bench-parallel.yml up -d
./benchmarks/seed/seed-benchmark-data.sh
./benchmarks/run-queue-driven-benchmark.sh
```

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

### Running billing platform with Docker Compose

The billing platform services can be deployed locally via Docker Compose:

**Start all billing services:**
```bash
docker compose -f docker-compose.yml -f docker-compose.billing.yml up -d
```

**Service endpoints:**
- customer-service: http://localhost:8081
- rate-service: http://localhost:8082
- regulatory-service: http://localhost:8083
- billing-worker-service: http://localhost:8084
- billing-orchestrator-service: http://localhost:8085
- postgres: localhost:15432

**Stop and clean up:**
```bash
docker compose -f docker-compose.yml -f docker-compose.billing.yml down -v
```

**Seed test data:**
```bash
./scripts/seed-billing-test-data.sh
```

This creates sample customers, meters, billing periods, and meter reads for manual testing.

### Running E2E tests

End-to-end integration tests validate the complete billing workflow across all five microservices.

**Run E2E test suite:**
```bash
./scripts/run-e2e-tests.sh
```

This script:
1. Starts all billing services via docker-compose
2. Waits for services to be healthy
3. Runs the E2E test suite (`:e2e-tests` module)
4. Tears down services (unless `--keep-running` is specified)

**Keep services running after tests:**
```bash
./scripts/run-e2e-tests.sh --keep-running
```

**Run tests manually (services already running):**
```bash
./scripts/gradlew-java21.sh :e2e-tests:test --no-daemon
```

E2E tests are located in `e2e-tests/src/test/kotlin/com/example/usbilling/e2e/` and use RestAssured for HTTP interactions.

## High-level architecture

The system contains two major platforms:
1. **Billing platform** (active development) - Utility billing for electric, gas, water, and other services
2. **Payroll platform** (legacy, deprecated) - Employee payroll processing

### Billing Platform Architecture

The billing platform is built around a functional billing core with microservices for customer management, rate application, regulatory compliance, and bill lifecycle management.

**Core modules:**
- `shared-kernel`: Shared value types (`UtilityId`, `CustomerId`, `Money`, `ServiceType`, `Address`)
- `billing-domain`: Pure calculation logic for utility billing
  - `com.example.usbilling.billing.model.*` - domain models (`CustomerSnapshot`, `MeterReadPair`, `BillInput`, `BillResult`, `RateTariff`, `RegulatoryCharge`, `UsageCharge`, etc.)
  - `com.example.usbilling.billing.engine.*` - calculators (`BillingEngine`, `RateApplier`, `TimeOfUseCalculator`)
  - Framework-agnostic, side-effect-free calculation logic

**Service boundaries:**
- **customer-service (port 8081)**
  - Owns customer, meter, billing period, and meter read data
  - Implements `CustomerSnapshotProvider` and `BillingPeriodProvider` ports
  - Database: `us_billing_customer` (postgres)
  - HTTP endpoints under `/utilities/{utilityId}`

- **rate-service (port 8082)**
  - Owns rate tariffs, rate components, and time-of-use schedules
  - Implements `RateContextProvider` to return applicable tariffs
  - Database: `us_billing_rate` (postgres)
  - Supports flat, tiered, time-of-use (TOU), and demand rate structures

- **regulatory-service (port 8083)**
  - Owns regulatory charges (base charges, riders, taxes, fees)
  - Implements `RegulatoryChargeProvider`
  - In-memory data repository (no database)
  - Supports MI, OH, IL, CA, NY states

- **billing-worker-service (port 8084)**
  - Orchestrates bill calculation by calling customer, rate, and regulatory services
  - Consumes HTTP APIs from other services using WebClient
  - Stateless computation service

- **billing-orchestrator-service (port 8085)**
  - Manages bill lifecycle (DRAFT → COMPUTING → FINALIZED → ISSUED → VOIDED)
  - Persists bills, bill lines, and audit events
  - Database: `us_billing_orchestrator` (postgres)
  - HTTP endpoints for bill creation, status updates, void operations

**Docker Compose topology:**
- Base: `docker-compose.yml` (postgres, pgbouncer)
- Billing overlay: `docker-compose.billing.yml` (all 5 billing services)
- Usage: `docker compose -f docker-compose.yml -f docker-compose.billing.yml up`

**Testing approach:**
- Unit tests: Each module tests its domain logic in isolation
- E2E tests: `e2e-tests` module validates full workflow across all services
- Test orchestration: `./scripts/run-e2e-tests.sh` automates service startup, testing, teardown
- Test data seeding: `./scripts/seed-billing-test-data.sh` creates sample customers and meters

### Payroll Platform Architecture (Legacy)

The payroll platform is deprecated and undergoing migration to the billing domain.

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
- **Payroll orchestrator service (`payroll-orchestrator-service`)**
  - Owns payrun lifecycle APIs and orchestration (off-cycle, void/reissue, retro).
  - Representative endpoints:
    - `POST /employers/{employerId}/payruns/finalize`
    - `GET /employers/{employerId}/payruns/{payRunId}`
- **Payroll worker service (`payroll-worker-service`)**
  - Performs queue-driven per-employee paycheck computation for orchestrator-driven payruns.
  - Exposes a demo endpoint and (optional) benchmark/dev endpoints:
    - `GET /dry-run-paychecks`
    - `POST /benchmarks/employers/{employerId}/hr-backed-pay-period` (feature-flagged)
  - Uses typed clients (`HrClient`, `TaxClient`, `LaborStandardsClient`) to call service HTTP APIs.

Other modules such as `persistence-core`, `messaging-core`, and `web-core` exist to factor out shared infrastructure concerns; when adding new cross-cutting integration code, prefer extending these modules over duplicating plumbing in individual services.

### Separation of concerns

- The **domain core** (`shared-kernel` + `payroll-domain`) must stay framework-agnostic and side-effect free.
- Service modules encapsulate persistence, HTTP, and operational concerns while depending on the domain only via its public types and ports.
- Configuration-heavy content (tax rules, labor standards) lives as versioned CSV/JSON artifacts in `tax-content` (tax) and the relevant service modules (e.g. labor) and is compiled/imported via explicit Gradle tasks.

### Year and coverage assumptions

- Current rules and standards are primarily modeled for the 2025 tax and labor year.
- When generating new tax or labor content, prefer to follow the existing 2025-focused pipelines unless explicitly expanding year coverage.

This architecture is intended to keep payroll calculations deterministic and testable while allowing HR, Tax, Labor, and configuration pipelines to evolve independently in their own modules and services.