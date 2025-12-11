# Pub 15-T Federal Withholding Compliance Implementation Plan
This document is a detailed, AI-friendly implementation plan for refactoring the federal withholding pipeline to align with IRS Publication 15-T while preserving the functional core and clean architecture of the `us-payroll-platform`.

## Modules in scope
- `payroll-domain` – pure domain and tax engine logic
- `tax-service` – tax catalog, configuration, and Spring wiring
- `hr-service` – HR DB schema and W-4 storage/access

## Phase 0 – Discovery and guardrails

Status: **Completed on 2025-12-10**

1. Scan existing FIT-related code (read-only)
   - `payroll-domain`:
     - `payroll/model/EmployeeTypes.kt`
     - `payroll/engine/BasisBuilder.kt`
     - `payroll/engine/TaxesCalculator.kt`
   - `tax-service`:
     - `tax/service/FederalWithholdingCalculator.kt`
     - `tax/service/FederalWithholdingCalculatorTest.kt`
     - `tax/service/FederalWithholdingIntegrationTest.kt`
     - `tax/tools/Pub15TWageBracketGenerator.kt`
     - `tax/persistence/*Pub15T*Test.kt`
     - `src/main/resources/tax-config/federal-2025-*.json`
   - `hr-service`:
     - `hr/db/JdbcHrAdapters.kt`
     - `src/main/resources/db/migration/V001__create_hr_schema.sql`
2. Run current tests once to establish a baseline
   - From repo root:
     - `./gradlew :payroll-domain:test`
     - `./gradlew :tax-service:test`
     - `./gradlew :hr-service:test`
3. Create a feature branch (manual human step, not for AI)

## Phase 1 – New Pub 15-T engine skeleton in `payroll-domain`

Status: **Completed on 2025-12-10**

Goal: Introduce types and skeleton logic in a new, pure package, without changing behavior yet.

1. Create package and files
   - Add:
     - `payroll-domain/src/main/kotlin/com/example/uspayroll/payroll/engine/pub15t/WithholdingProfile.kt`
     - `payroll-domain/src/main/kotlin/com/example/uspayroll/payroll/engine/pub15t/FederalWithholdingEngine.kt`
     - `payroll-domain/src/main/kotlin/com/example/uspayroll/payroll/engine/pub15t/W4Version.kt`
2. Define core types (pure data)
   - In `W4Version.kt`:
     - `enum class W4Version { LEGACY_PRE_2020, MODERN_2020_PLUS }`
   - In `WithholdingProfile.kt`:
     - Define a `data class WithholdingProfile` with fields (nullable where needed):
       - `val filingStatus: FilingStatus`
       - `val w4Version: W4Version`
       - `val step3AnnualCredit: Money?`
       - `val step4OtherIncomeAnnual: Money?`
       - `val step4DeductionsAnnual: Money?`
       - `val extraWithholdingPerPeriod: Money?`
       - `val step2MultipleJobs: Boolean`
       - `val federalWithholdingExempt: Boolean`
       - `val isNonresidentAlien: Boolean`
       - Optional metadata: `val firstPaidBefore2020: Boolean?` (for NRA tables)
   - In `FederalWithholdingEngine.kt`:
     - Add stubs for:
       - `enum class WithholdingMethod { PERCENTAGE, WAGE_BRACKET }`
       - `data class FederalWithholdingResult(val amount: Money, val trace: List<TraceStep>)`
       - A main entry point (implementation will be added later):
         - `fun computeWithholding(input: PaycheckInput, bases: BasisComputation, profile: WithholdingProfile, federalRules: List<TaxRule>, method: WithholdingMethod): FederalWithholdingResult`
3. Add basic unit tests to ensure compilation
   - New test file:
     - `payroll-domain/src/test/kotlin/com/example/uspayroll/payroll/engine/pub15t/WithholdingProfileTest.kt`
   - Tests can be simple instantiation and equality checks.
4. Run `./gradlew :payroll-domain:test` and fix any compilation issues

## Phase 2 – Extend `EmployeeSnapshot` and HR for W-4 metadata

Status: **Completed on 2025-12-10**

Goal: Add enough metadata to support modern W-4, legacy W-4, and the computational bridge.

1. Extend domain model
   - File: `payroll-domain/src/main/kotlin/com/example/uspayroll/payroll/model/EmployeeTypes.kt`
   - Extend `data class EmployeeSnapshot` with new optional fields, each with a default value:
     - `val w4Version: W4Version? = null`
     - `val legacyAllowances: Int? = null`
     - `val legacyAdditionalWithholdingPerPeriod: Money? = null`
     - `val legacyMaritalStatus: String? = null` (could later be tightened to an enum)
     - `val w4EffectiveDate: LocalDate? = null`
2. Update HR DB schema
   - File: `hr-service/src/main/resources/db/migration/V001__create_hr_schema.sql`
   - Add nullable columns to `employee` (or equivalent) table:
     - `w4_version VARCHAR(20)`
     - `legacy_allowances INTEGER`
     - `legacy_additional_withholding_cents BIGINT`
     - `legacy_marital_status VARCHAR(32)`
     - `w4_effective_date DATE`
3. Update HR JDBC mapping
   - File: `hr-service/src/main/kotlin/com/example/uspayroll/hr/db/JdbcHrAdapters.kt`
   - Extend `EmployeeWithCompRow` with the new fields.
   - Map columns to fields in `EmployeeWithCompRowMapper`.
   - Pass these values into the `EmployeeSnapshot` constructor.
4. Add/extend HR tests
   - File: `hr-service/src/test/kotlin/com/example/uspayroll/hr/db/JdbcHrAdaptersTest.kt`
   - Add a test that inserts a row including the new columns and verifies that `EmployeeSnapshot` fields are populated correctly.
5. Run
   - `./gradlew :hr-service:test`
   - `./gradlew :payroll-domain:test`

## Phase 3 – Legacy W-4 computational bridge
Goal: Convert legacy W-4 fields into a `WithholdingProfile` equivalent to a 2020+ W-4 using the IRS computational bridge.

1. Create `LegacyW4Bridge`
   - File: `payroll-domain/src/main/kotlin/com/example/uspayroll/payroll/engine/pub15t/LegacyW4Bridge.kt`
   - Add a pure function:
     - `fun fromLegacy(employee: EmployeeSnapshot): WithholdingProfile`
   - Implementation outline:
     - Map `legacyMaritalStatus` to a `FilingStatus`.
     - Compute synthetic Step 4(a) and Step 4(b) values based on Pub 15-T constants and number of allowances.
     - Map `legacyAdditionalWithholdingPerPeriod` to `extraWithholdingPerPeriod`.
     - Set `w4Version = W4Version.LEGACY_PRE_2020`.
2. Add a normalizer helper for all W-4s
   - Either in `FederalWithholdingEngine.kt` or a new file in the same package, add:
     - `fun profileFor(employee: EmployeeSnapshot): WithholdingProfile`
   - Logic:
     - If `employee.w4Version == W4Version.LEGACY_PRE_2020`, call `LegacyW4Bridge.fromLegacy(employee)`.
     - Otherwise, create a `WithholdingProfile` from modern fields:
       - `filingStatus = employee.filingStatus`
       - `step3AnnualCredit = employee.w4AnnualCreditAmount`
       - `step4OtherIncomeAnnual = employee.w4OtherIncomeAnnual`
       - `step4DeductionsAnnual = employee.w4DeductionsAnnual`
       - `extraWithholdingPerPeriod = employee.additionalWithholdingPerPeriod`
       - `step2MultipleJobs = employee.w4Step2MultipleJobs`
       - `federalWithholdingExempt = employee.federalWithholdingExempt`
       - `isNonresidentAlien = employee.isNonresidentAlien`
       - `w4Version = W4Version.MODERN_2020_PLUS`
3. Unit tests
   - `payroll-domain/src/test/kotlin/com/example/uspayroll/payroll/engine/pub15t/LegacyW4BridgeTest.kt`
     - Verify that legacy inputs produce the expected `WithholdingProfile` fields.
   - `payroll-domain/src/test/kotlin/com/example/uspayroll/payroll/engine/pub15t/FederalWithholdingEngineProfileTest.kt`
     - Verify that `profileFor` picks correct behavior based on `w4Version`.
4. Run `./gradlew :payroll-domain:test`

## Phase 4 – Nonresident alien (NRA) wage adjustments

Status: **Completed on 2025-12-10**

Goal: Implement Pub 15-T NRA extra wages tables as pure logic and wire them into the engine.

1. Create NRA helper
   - File: `payroll-domain/src/main/kotlin/com/example/uspayroll/payroll/engine/pub15t/NraAdjustment.kt`
   - Add function:
     - `fun extraWagesForNra(frequency: PayFrequency, w4Version: W4Version, firstPaidBefore2020: Boolean): Money`
   - Implement Pub 15-T tables as constants keyed by `(frequency, w4Version, firstPaidBefore2020)`.
2. Wire NRA adjustments into the engine
   - In `FederalWithholdingEngine.computeWithholding`:
     - When computing per-period taxable wages, if `profile.isNonresidentAlien`:
       - Determine `firstPaidBefore2020` (from `w4EffectiveDate` or `hireDate` heuristics).
       - Compute `extra = extraWagesForNra(...)`.
       - Add `extra` to the per-period `FederalTaxable` wages before annualization and rule application.
3. Unit tests
   - `payroll-domain/src/test/kotlin/com/example/uspayroll/payroll/engine/pub15t/NraAdjustmentTest.kt`
     - Verify returned amounts for selected combinations of frequency and W-4 version.
   - Add a couple of scenario tests in `FederalWithholdingEngineTest.kt` to verify NRA employees withhold more than equivalent resident employees.
4. Run `./gradlew :payroll-domain:test`

## Phase 5 – Step 2 (multiple jobs) variants in configs and schema

Status: **Completed on 2025-12-10**

Goal: Make it possible to select different FIT rules when W-4 Step 2 multiple jobs checkbox is checked.

1. Extend tax config model
   - File: `tax-service/src/main/kotlin/com/example/uspayroll/tax/config/TaxRuleConfig.kt`
   - Add an optional field:
     - `val fitVariant: String? = null`
2. Extend DB schema for tax rules
   - File: `tax-service/src/main/resources/db/migration/V1__init_tax_rule.sql`
   - Add column:
     - `fit_variant VARCHAR(32)` (nullable)
3. Update repository mapping
   - File: `tax-service/src/main/kotlin/com/example/uspayroll/tax/persistence/JooqTaxRuleRepository.kt`
   - Ensure `fit_variant` is mapped into the in-memory representation (`TaxRuleRecord`/`TaxRuleConfig`) and back.
4. Update JSON configs
   - Files:
     - `tax-service/src/main/resources/tax-config/federal-2025-pub15t.json`
     - `tax-service/src/main/resources/tax-config/federal-2025-pub15t-weekly.json`
     - `tax-service/src/main/resources/tax-config/federal-2025-pub15t-wage-bracket-biweekly.json`
   - Add `"fitVariant": "STANDARD"` to existing rules.
   - Define additional rules for Step 2 variants with `"fitVariant": "STEP2_CHECKBOX"` and distinct IDs (e.g. `US_FED_FIT_2025_PUB15T_SINGLE_STEP2`).
5. Selection logic in tax catalog
   - File: `tax-service/src/main/kotlin/com/example/uspayroll/tax/impl/DbTaxCatalog.kt` or equivalent catalog provider
   - Extend query logic to optionally filter rules by `fitVariant`.
   - Option: define a small local enum `FitVariant { STANDARD, STEP2_CHECKBOX }` in tax-service and convert strings.
6. Golden tests
   - Extend or add a golden test (e.g. `Pub15TFederalGoldenTest`) to assert that for each filing status, both STANDARD and STEP2_CHECKBOX variants are present once configs are updated.
7. Run `./gradlew :tax-service:test`

## Phase 6 – Implement percentage-method logic in `FederalWithholdingEngine`

Status: **Completed on 2025-12-10**

Goal: Implement Pub 15-T percentage-method withholding using `TaxesCalculator` and Pub 15-T rules.

1. Rule selection helper
   - In `FederalWithholdingEngine.kt`, add:
     - `private fun selectBracketedFitRule(federalRules: List<TaxRule>, profile: WithholdingProfile, fitVariant: String): TaxRule.BracketedIncomeTax`
   - Filter logic:
     - Rule type: `TaxRule.BracketedIncomeTax`
     - Jurisdiction: FEDERAL / code `"US"`
     - Basis: `TaxBasis.FederalTaxable`
     - Filing status: matches `profile.filingStatus`
     - Fit variant: matches `fitVariant` (or null when not configured)
2. Adjusted wages computation
   - Determine `periodsPerYear` from `input.period.frequency`.
   - Starting basis:
     - `val periodFederalTaxable = bases.bases[TaxBasis.FederalTaxable]`.
   - NRA adjustments (if needed) already applied as described in Phase 4.
   - Annualized wages:
     - `val annualWages = periodFederalTaxable.amount * periodsPerYear`
   - Apply W-4 Step 4(a)/4(b):
     - Add `profile.step4OtherIncomeAnnual`.
     - Subtract `profile.step4DeductionsAnnual`.
   - Result is `adjustedAnnualWages`.
3. Call `TaxesCalculator` for annual tax
   - Build a synthetic `TaxContext` with only the selected rule:
     - `TaxContext(federal = listOf(selectedRule), state = emptyList(), local = emptyList(), employerSpecific = emptyList())`
   - Build `bases` map for the call:
     - `TaxBasis.FederalTaxable` mapped to `Money(adjustedAnnualWages)`.
   - Use an annual-frequency `PayPeriod` (or reuse the existing period with `frequency = ANNUAL`).
   - Construct a `PaycheckInput` specifically for this computation (can reuse IDs from original input but with frequency adjusted to ANNUAL for clarity).
   - Call `TaxesCalculator.computeTaxes(...)` and extract the tax line whose `ruleId` equals the selected rule.
4. Apply W-4 Step 3 credits and de-annualize
   - Compute annual tax:
     - `val annualTax = fitLine.amount.amount`
   - Apply Step 3 credit:
     - `val credit = profile.step3AnnualCredit?.amount ?: 0L`
     - `val netAnnualTax = max(0L, annualTax - credit)`
   - Convert to per-period:
     - `val perPeriodTax = netAnnualTax / periodsPerYear`
5. Apply per-period extra withholding
   - Add `profile.extraWithholdingPerPeriod?.amount` to `perPeriodTax`.
6. Return result
   - `FederalWithholdingResult(amount = Money(perPeriodTax), trace = /* optional trace steps */)`
7. Unit tests
   - File: `payroll-domain/src/test/kotlin/com/example/uspayroll/payroll/engine/pub15t/FederalWithholdingEnginePercentageMethodTest.kt`
   - Write tests with small in-memory FIT rules verifying:
     - Monotonicity (higher wages → more tax).
     - Step 3 reduces withholding.
     - Step 4 other income increases withholding, Step 4 deductions decrease it.
8. Run `./gradlew :payroll-domain:test`

## Phase 7 – Implement wage-bracket method path

Status: **Completed on 2025-12-10**

Goal: Support Pub 15-T wage-bracket method via `TaxRule.WageBracketTax` and `TaxesCalculator`.

1. Rule selection for wage brackets
   - In `FederalWithholdingEngine.kt`, add:
     - `private fun selectWageBracketFitRule(federalRules: List<TaxRule>, profile: WithholdingProfile, fitVariant: String): TaxRule.WageBracketTax`
   - Similar filters as for the bracketed rule, but with `TaxRule.WageBracketTax` and possibly frequency-specific rule IDs.
2. Compute per-period wages
   - Use `periodFederalTaxable` from `bases.bases[TaxBasis.FederalTaxable]`, including NRA adjustment if applicable.
   - No annualization; wage-bracket tables operate directly on per-period wages.
3. Call `TaxesCalculator`
   - Build a `TaxContext` with only the wage-bracket rule.
   - `bases` map: `TaxBasis.FederalTaxable` → per-period `Money`.
   - Use the real `PaycheckInput` period (correct frequency).
   - Call `TaxesCalculator.computeTaxes` and extract the FIT tax line for the wage-bracket rule.
4. Apply extra per-period withholding
   - Add `profile.extraWithholdingPerPeriod?.amount`.
5. Tests
   - File: `payroll-domain/src/test/kotlin/com/example/uspayroll/payroll/engine/pub15t/FederalWithholdingEngineWageBracketTest.kt`
   - Use `federal-2025-pub15t-wage-bracket-biweekly.json` or in-memory rules to assert monotonic behavior across wage bands.
6. Run `./gradlew :payroll-domain:test`

## Phase 8 – Refactor `tax-service` adapter to use the new engine

Status: **Completed on 2025-12-10**

Goal: Replace the approximate `DefaultFederalWithholdingCalculator` implementation with a thin adapter over `FederalWithholdingEngine`, without changing the public interface.

1. Decide where to compute bases
   - Preferably, reuse `BasisBuilder` to compute `BasisComputation` for the paycheck.
   - If `FederalWithholdingCalculator` does not currently have direct access to earnings/deductions, adjust its usage so that either:
     - The caller supplies sufficient data to reconstruct bases, or
     - The adapter can build a minimal `BasisContext` from the available data.
2. Reimplement `DefaultFederalWithholdingCalculator`
   - File: `tax-service/src/main/kotlin/com/example/uspayroll/tax/service/FederalWithholdingCalculator.kt`
   - Simplify `DefaultFederalWithholdingCalculator.computeWithholding`:
     - If `employeeSnapshot.federalWithholdingExempt` → return `Money(0L)`.
     - Obtain the current `TaxContext` (as integration tests already do).
     - Build `BasisContext` and call `BasisBuilder.compute` to obtain `BasisComputation`.
     - Build `WithholdingProfile` using `profileFor(employeeSnapshot)` from the domain `pub15t` package.
     - Decide `WithholdingMethod` based on configuration (see Phase 9).
     - Choose `fitVariant` (`"STANDARD"` or `"STEP2_CHECKBOX"`) based on `profile.step2MultipleJobs`.
     - Call `FederalWithholdingEngine.computeWithholding` with the above inputs and return `result.amount`.
3. Keep Spring wiring intact
   - File: `tax-service/src/main/kotlin/com/example/uspayroll/tax/config/TaxServiceConfig.kt`
   - Keep the bean definition:
     - `fun federalWithholdingCalculator(): FederalWithholdingCalculator = DefaultFederalWithholdingCalculator()`
   - Ensure the new implementation remains framework-agnostic and only uses domain code.
4. Update existing tests
   - `tax-service/src/test/kotlin/com/example/uspayroll/tax/service/FederalWithholdingCalculatorTest.kt`
     - Update assertions if they depended on the old approximation; they should still focus on directional behavior.
   - `tax-service/src/test/kotlin/com/example/uspayroll/tax/service/FederalWithholdingIntegrationTest.kt`
     - Ensure it passes with the new implementation.
5. Run `./gradlew :tax-service:test`

## Phase 9 – Configuration and feature flags

Status: **Completed on 2025-12-10**

Goal: Allow safe rollout of the new Pub 15-T logic and optional wage-bracket method.

1. Introduce configuration properties
   - In `tax-service` application configuration (e.g. `application.yml`):
     - `tax.federalWithholding.method` with default `PERCENTAGE`.
     - `tax.federalWithholding.pub15tStrictMode` with default `false`.
2. Read config in Spring
   - Create a config class or use `@Value` injection in `TaxServiceConfig` to read these properties.
3. Use flags in adapter
   - In `DefaultFederalWithholdingCalculator`:
     - If `method == WAGE_BRACKET`, call the wage-bracket path in the engine.
     - If `method == PERCENTAGE`, call the percentage-method path.
     - If `pub15tStrictMode == false`, optionally skip some strict behaviors (e.g. legacy bridge or Step 2 variants) during an initial rollout.
4. Add tests
   - Spring tests or unit tests verifying that different configuration values lead to different method choices or strictness.
5. Run `./gradlew :tax-service:test`

## Phase 10 – End-to-end and regression validation

Note: Phase 10 focuses primarily on modern (2020+) W-4 flows. The numeric IRS
"computational bridge" for legacy pre-2020 W-4s remains a known gap and will be
addressed in a follow-on Phase 3b.

Goal: Ensure the full system behaves correctly across services and matches Pub 15-T expectations for key scenarios.

1. Extend worker-service tests
   - In `payroll-worker-service` tests (e.g. `*IntegrationTest.kt`):
     - Add scenarios that match or approximate IRS Pub 15-T examples:
       - Single filer, biweekly pay, with dependents and Step 3 credits.
       - Step 2 multiple jobs vs not, same wages.
       - Nonresident alien vs resident with the same wages and W-4 fields.
     - Assert FIT amounts on final paycheck outputs.
2. Run full test suite
   - From repo root: `./gradlew test`
3. Side-by-side comparison (manual/ops)
   - For a curated set of employee/paycheck cases:
     - Compare old vs new withholding amounts and IRS worksheet results.
     - Confirm that strict mode (when enabled) matches Pub 15-T expectations.
4. Prepare rollout documentation (human step)
   - Describe:
     - New W-4 fields and their meaning.
     - How withholding behavior may change.
     - Configuration knobs for enabling strict Pub 15-T mode or wage-bracket method.

## Phase 3b – Legacy W-4 computational bridge numerics (Deferred)
Goal: Implement the full IRS computational bridge for pre-2020 W-4s, mapping
legacy allowances and marital status into synthetic 2020+ W-4 fields.

Status: **Deferred**

1. Implement numeric mapping in `LegacyW4Bridge` / `WithholdingProfiles` based on
   IRS Pub. 15-T bridge tables.
2. Add tests that mirror IRS bridge examples to validate the mapping.
3. Run `./gradlew :payroll-domain:test` and re-run key service-level tests.

---
This plan is intended to be executed incrementally by an AI agent (or humans) working within the repo, with each phase validated via the specified tests before proceeding to the next phase.

## Appendix A – Pub 15-T wage-bracket content sourcing and governance

This appendix documents the enterprise-grade approach the project uses to source, transform, and validate IRS Pub 15-T wage-bracket content. It is intended for auditors, compliance reviewers, and future maintainers.

### A.1 Authoritative source material

- The numerical values for federal income tax wage-bracket rules (including Step 2 multiple-jobs variants) are taken from the official IRS Publication 15-T for the relevant year.
- For tax year 2025, the governing document is Publication 15-T (2025), Wage Bracket Method Tables for Manual Payroll Systems With Forms W-4 From 2020 or Later, biweekly payroll period section.
- The publication itself is treated as an offline, versioned source of truth and is not fetched dynamically at application runtime.
- For internal auditability, each yearly Pub. 15-T artifact is stored in an internal compliance repository with a stable path and checksum. For example:
  - Path (example): `compliance-sources/pub15t/2025/p15t--2025.pdf`.
  - Metadata recorded alongside the artifact: IRS URL, revision date, and a SHA-256 checksum.
- This repo does not store the IRS PDF directly; it instead references the internal path and assumes ops/compliance maintain the artifact and its checksum according to organizational policy.

### A.2 Curated CSV as primary internal representation

- The project maintains a year- and frequency-specific CSV file under version control:
  - `tax-service/src/main/resources/wage-bracket-2025-biweekly.csv`.
- This CSV encodes, per wage band, filing status, and variant:
  - `frequency` – e.g. `BIWEEKLY`.
  - `filingStatus` – `SINGLE`, `MARRIED`, or `HEAD_OF_HOUSEHOLD`.
  - `variant` – `STANDARD` or `STEP2_CHECKBOX`.
  - `minCents` / `maxCents` – wage band endpoints in cents (inclusive lower bound, exclusive upper bound; an empty `maxCents` denotes an open-ended top band).
  - `taxCents` – the IRS table tax amount in cents for that band.
- The CSV is generated from the IRS publication via a one-off extraction script and is then reviewed and checked in. Subsequent builds and deployments treat this CSV as the canonical curated representation of the IRS table.

### A.3 Deterministic CSV → JSON transformation

- The TaxRule configuration layer remains the single input to the `tax_rule` database table. For wage-bracket rules, JSON files under `tax-service/src/main/resources/tax-config` are generated from the curated CSV using a deterministic, side-effect-free transformer.
- The core components are:
  - `tax-service/src/main/kotlin/com/example/uspayroll/tax/tools/WageBracketCsvParser.kt`
    - Parses the CSV schema described above into in-memory rows.
    - Tolerates comment lines (prefixed with `#`) and treats the first non-comment line as the header row.
    - Groups rows by `(filingStatus, variant)` and converts each group into a `TaxRuleConfig` with `ruleType = "WAGE_BRACKET"`, per-band `taxCents`, and an appropriate `fitVariant` (`STANDARD` vs `STEP2_CHECKBOX`).
  - `tax-service/src/main/kotlin/com/example/uspayroll/tax/tools/WageBracketCsvImporter.kt`
    - CLI-style entrypoint that calls `WageBracketCsvParser` and writes a `TaxRuleFile` JSON document.
- A dedicated Gradle task wires this together without starting Spring Boot:
  - Task: `:tax-service:generateFederal2025BiweeklyWageBrackets` in `tax-service/build.gradle.kts`.
  - Behavior:
    - Reads `wage-bracket-2025-biweekly.csv` from `src/main/resources`.
    - Generates `tax-service/src/main/resources/tax-config/federal-2025-pub15t-wage-bracket-biweekly.json`.
    - Sets `id` prefixes to `US_FED_FIT_2025_PUB15T_WB`, with suffixes derived from filing status and variant (for example, `US_FED_FIT_2025_PUB15T_WB_SINGLE_BI_STEP2`).
    - Applies effective dates `2025-01-01` through `9999-12-31`.

### A.4 Validation and tests

- Config-level validation:
  - All generated `TaxRuleConfig` objects are validated by `TaxRuleConfigValidator.validateRules` prior to import.
  - For `ruleType = "WAGE_BRACKET"` rules, the validator enforces:
    - Non-empty brackets.
    - Strictly increasing `upToCents` where non-null.
    - Presence of at least one open-ended bracket (`upToCents = null`).
    - Presence of `taxCents` on every bracket.
- Database-level import:
  - Tests use `TaxRuleConfigImporter` together with `H2TaxTestSupport` to import the generated JSON into an in-memory `tax_rule` table, exercising the same schema and importer used in production.
- Golden/structural tests:
  - `Pub15TWageBracketBiweeklyGoldenTest` verifies that:
    - Federal wage-bracket rules are present in the loaded `TaxContext`.
    - Each wage-bracket rule has more than a trivial number of bands.
    - Each rule’s `upTo` thresholds are strictly increasing and include an open-ended top band.
    - Taxes are non-decreasing across brackets for at least one canonical rule.
- Service-level behavior tests:
  - `FederalWithholdingIntegrationTest` exercises `DefaultFederalWithholdingCalculator` in `WAGE_BRACKET` mode, including Step 2 multiple-jobs scenarios, confirming that the DB-backed wage-bracket rules are applied correctly.

### A.5 Runtime usage and separation of concerns

- The wage-bracket JSON produced from the CSV is treated as a static configuration artifact:
  - Imported into the `tax_rule` table via migrations or one-time admin tooling.
  - Loaded at runtime by the `TaxCatalog`/`CatalogBackedTaxContextProvider` stack into a `TaxContext`.
- The payroll-domain engine (`FederalWithholdingEngine`) never calls IRS endpoints and never parses IRS publications directly; it only consumes the normalized tax rules surfaced through `TaxContext`.

### A.6 Annual update process (pattern)

- For each new tax year, the intended process is:
  1. Acquire the new Pub. 15-T publication and store it as an internal artifact.
  2. Generate or curate new CSVs for each relevant pay frequency (for example, `wage-bracket-2026-biweekly.csv`), following the same schema.
  3. Run the corresponding Gradle generator task(s) to produce `TaxRuleFile` JSON.
  4. Validate the generated JSON via `validateTaxConfig` and golden tests.
  5. Import the JSON into the `tax_rule` table in non-production and production environments through controlled migrations.

This appendix documents the organization’s approach to sourcing and validating Pub. 15-T wage-bracket tables: IRS publications are treated as authoritative but offline inputs; CSVs under version control are the canonical curated representation; JSON TaxRuleFiles are deterministically generated from CSVs; and database contents are validated via configuration and golden tests before being used by the payroll engine.
