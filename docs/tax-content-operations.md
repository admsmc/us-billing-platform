# Tax Content Operations Runbook

This runbook describes the operational process for managing federal tax content for the US payroll platform, with a focus on IRS Pub. 15-T wage-bracket tables.

The goals are:
- Ensure tax content is sourced from authoritative IRS publications.
- Keep all transformations deterministic and auditable.
- Allow ops/compliance to perform yearly updates without changing application code.

## 1. Scope

This runbook covers:
- Federal income tax wage-bracket tables from IRS Publication 15-T.
- The biweekly wage-bracket configuration used by tax-service.
- The CSV → JSON → DB pipeline in `tax-service` (with shared content living in `tax-content`).

State income tax content and other tax rules have their own pipelines and are not described here.

## 2. Artifacts and locations

Key paths in the repo:
- Curated Pub. 15-T wage-bracket CSV for the current year:
  - `tax-content/src/main/resources/wage-bracket-2025-biweekly.csv`
- Generated TaxRuleFile JSON for biweekly wage-brackets:
  - `tax-content/src/main/resources/tax-config/federal-2025-pub15t-wage-bracket-biweekly.json`
- CSV → JSON transformer:
  - `tax-service/src/main/kotlin/com/example/uspayroll/tax/tools/WageBracketCsvParser.kt`
  - `tax-service/src/main/kotlin/com/example/uspayroll/tax/tools/WageBracketCsvImporter.kt`
- Gradle generator task:
  - `:tax-service:generateFederal2025BiweeklyWageBrackets` (defined in `tax-service/build.gradle.kts`)
- DB importer used in tests and migrations:
  - `tax-impl/src/main/kotlin/com/example/uspayroll/tax/persistence/TaxRuleConfigImporter.kt`

## 3. Yearly update checklist (Pub. 15-T wage-brackets)

Use this checklist when updating to a new tax year (for example, 2026).

### 3.1 Acquire and register IRS source

1. Download the official IRS Publication 15-T PDF for the new year.
2. Store it in your internal document system or compliance repository with:
   - Year, revision date, and IRS URL.
   - A checksum (for example, SHA-256) for integrity verification.
   - A stable internal path. For 2025, a representative pattern is:
     - `compliance-sources/pub15t/2025/p15t--2025.pdf`.
3. Record the artifact location and checksum in your internal compliance log and, where appropriate, reference it in engineering docs (for example, Appendix A in `docs/pub15t-refactor-plan.md`).

### 3.2 Curate CSV for the new year

1. Create a new CSV under `tax-content/src/main/resources`, following the existing naming pattern. For example:
   - `wage-bracket-2026-biweekly.csv`
2. Populate this CSV from the new Pub. 15-T wage-bracket table (Forms W-4 from 2020 or later, biweekly payroll period):
   - Columns: `frequency,filingStatus,variant,minCents,maxCents,taxCents`.
   - Filing statuses: `SINGLE`, `MARRIED`, `HEAD_OF_HOUSEHOLD`.
   - Variants: `STANDARD`, `STEP2_CHECKBOX`.
   - Convert dollar values to cents (multiply by 100).
   - Leave `maxCents` blank for the top open-ended band.
3. Have a tax/Payroll SME review and sign off on the CSV against the IRS table.
4. Commit the CSV to the repo with a clear commit message referencing the IRS publication and year.

### 3.3 Generate JSON from CSV

1. Add or update a Gradle task in `tax-service/build.gradle.kts` for the new year. Use the 2025 task as a template and adjust:
   - `--wageBracketCsv=wage-bracket-2026-biweekly.csv`
   - `--output=tax-config/federal-2026-pub15t-wage-bracket-biweekly.json`
   - `--baseIdPrefix=US_FED_FIT_2026_PUB15T_WB`
   - `--effectiveFrom` / `--effectiveTo` for the new year.
2. Run the generator from the repo root:

   ```sh
   ./gradlew :tax-service:generateFederal2026BiweeklyWageBrackets
   ```

3. Inspect the generated JSON to spot-check a few wage bands against Pub. 15-T.

### 3.4 Validate configuration and tests

From the repo root, run:

```sh
./gradlew :tax-service:validateTaxConfig
./gradlew :tax-service:test
```

These commands ensure:
- All TaxRuleFile JSON passes structural validation (including WAGE_BRACKET specifics).
- Golden tests such as `Pub15TWageBracketBiweeklyGoldenTest` succeed with the new data.
- Service-level tests, including `FederalWithholdingIntegrationTest`, still pass.

### 3.5 Import into database (non-prod → prod)

1. Use your existing database migration or admin tooling to import the new JSON into `tax_rule` in non-production environments. Typical flow:
   - Add a Flyway/Liquibase migration (in your infra repo) that, when executed, loads `federal-YYYY-pub15t-wage-bracket-biweekly.json` via `TaxRuleConfigImporter`. For example, a Flyway Java-based migration can:
     - Create a `DSLContext` for the target database.
     - Instantiate `TaxRuleConfigImporter` with that context.
     - Call `importFile` on the JSON path you ship with the service or mount into the container.
   - Alternatively, run an admin job that calls `TaxRuleConfigImporter.importFile` on `federal-YYYY-pub15t-wage-bracket-biweekly.json`.
2. Smoke test tax-service and any worker/orchestrator flows that rely on the new year.
3. Repeat the import in production during a controlled change window, following your normal release and approval processes, and record in your change log which CSV/JSON artifacts (by Git SHA/checksum) were imported into each environment.

## 4. Roles and approvals

- **Tax/Payroll SME**:
  - Confirms that the CSV values match the IRS publication.
  - Signs off on any interpretation decisions (for example, handling of Step 2, filing statuses).
- **Engineering**:
  - Maintains CSV → JSON tasks and import tooling.
  - Ensures tests and validators are kept up to date.
- **Ops/Compliance**:
  - Owns yearly execution of this runbook.
  - Ensures artifacts, checksums, and approvals are recorded for audit.

## 5. Key invariants

Ops and engineers should preserve the following invariants when making changes:

- IRS publications are **authoritative, offline inputs**; the running application never calls IRS endpoints directly.
- CSV files in `tax-content/src/main/resources` are the **canonical curated representation** of Pub. 15-T tables.
- JSON `TaxRuleFile` documents under `tax-content/src/main/resources/tax-config` are **deterministically generated** from CSV via code, not hand-edited.
- All JSON is validated and tested in CI/QA before import into production databases.
- The payroll-domain engine only consumes normalized tax rules via `TaxContext` and is decoupled from how those rules are sourced.

Following this runbook each year keeps the Pub. 15-T wage-bracket implementation traceable from IRS publication → CSV → JSON → database → runtime behavior, in a way that is suitable for external audit and internal compliance reviews.
