# tax-service

This module owns tax rule configuration and DB-backed tax catalogs for the payroll engine (federal, state, local, FICA, FUTA, etc.).

## State income tax configuration workflow

State income tax content is maintained as CSV files in Git, then compiled into JSON config and finally imported into the `tax_rule` table.

### 1. Authoritative CSVs (per tax year)

For each tax year (e.g. 2025), there are two CSV files under this module:

- Rules:
  - `src/main/resources/state-income-tax-2025-rules.csv`
- Brackets:
  - `src/main/resources/state-income-tax-2025-brackets.csv`

#### Rules CSV

Header:

```text
state_code,tax_year,filing_status,rule_type,basis,flat_rate,standard_deduction,additional_withholding,effective_from,effective_to,resident_state_filter,work_state_filter
```

Each row describes a high-level state income tax rule, typically one per `(state_code, tax_year, filing_status)`:

- `state_code` – 2-letter postal code (e.g. `CA`, `TX`).
- `tax_year` – e.g. `2025`.
- `filing_status` – `SINGLE`, `MARRIED`, `HEAD_OF_HOUSEHOLD`, etc., or blank.
- `rule_type` – `FLAT` or `BRACKETED`. Blank rows are treated as **not yet configured** and skipped.
- `basis` – usually `StateTaxable`. Blank defaults to `StateTaxable`.
- `flat_rate` – decimal rate (e.g. `0.050` for 5%) for flat states.
- `standard_deduction` – standard deduction in **dollars** (optional).
- `additional_withholding` – additional withholding in **dollars** (optional).
- `effective_from` – `YYYY-MM-DD`. Blank defaults to `tax_year-01-01`.
- `effective_to` – `YYYY-MM-DD`. Blank defaults to `9999-12-31`.
- `resident_state_filter` – which resident state(s) the rule applies to; blank defaults to `state_code`.
- `work_state_filter` – optional filter by work state.

Engineers own the schema and ID conventions; policy/ops collaborators typically edit only the numeric columns (`flat_rate`, `standard_deduction`, and bracket fields) via a spreadsheet view.

#### Brackets CSV

Header:

```text
state_code,tax_year,filing_status,bracket_index,up_to,rate
```

Each row describes one tax bracket:

- `state_code`, `tax_year`, `filing_status` – link the bracket to a rule row.
- `bracket_index` – integer order (1, 2, 3, ...).
- `up_to` – upper bound in **dollars** for this bracket; blank means no upper cap (top bracket).
- `rate` – decimal rate (e.g. `0.040` for 4%).

Brackets are grouped by `(state_code, tax_year, filing_status)` and attached to the corresponding rule in the rules CSV.

#### Arkansas two-table nuance

Arkansas publishes two individual income tax tables: a standard progressive table with multiple brackets and an alternative table for higher incomes. The current model encodes a single progressive schedule for `AR` in `state-income-tax-2025-brackets.csv`, preserving the true marginal rate structure (including the 0%, 2%, 3%, 3.4%, and 3.9% bands) while approximating the high-income alternative table. Do not "simplify" Arkansas back to a flat `FLAT` rule; any refinements should instead extend the bracket model (or the schema) to better capture the statutory tables.

### 2. CSV → JSON (TaxRuleFile)

A small tool converts the CSVs into a JSON `TaxRuleFile` that matches the `TaxRuleConfig` schema used by the importer:

- Parser: `StateIncomeTaxCsvParser` (`src/main/kotlin/com/example/uspayroll/tax/tools/StateIncomeTaxCsvParser.kt`).
- CLI: `StateIncomeTaxImporter` (`src/main/kotlin/com/example/uspayroll/tax/tools/StateIncomeTaxImporter.kt`).

To regenerate the JSON for a given year (from the project root):

```bash
./gradlew :tax-service:test --no-daemon
./gradlew :tax-service:runStateIncomeTaxImporter --no-daemon -PtaxYear=2025
```

This will read:

- `src/main/resources/state-income-tax-2025-rules.csv`
- `src/main/resources/state-income-tax-2025-brackets.csv`

and produce:

- `src/main/resources/tax-config/state-income-2025.json`

as a `TaxRuleFile` JSON document.

### 3. JSON → DB (`tax_rule` table)

The JSON output is then imported into the `tax_rule` table using the existing importer:

- `TaxRuleConfigImporter` (`src/main/kotlin/com/example/uspayroll/tax/persistence/TaxRuleConfigImporter.kt`).

This utility:

1. Reads one or more `TaxRuleFile` JSON documents from disk.
2. Inserts corresponding rows into the `tax_rule` table as SCD2 records, without updating or deleting existing rows.

Typical usage (from a migration or admin tool):

- Point `TaxRuleConfigImporter` at the `tax-config` directory for the relevant year (including `state-income-YYYY.json` and federal/local configs).
- Let the importer append rows to `tax_rule`.

### 4. Expanding to all 50 states

The `state-income-tax-2025-rules.csv` file already contains **skeleton rows for all 50 states** (AL through WY). To fully populate state income tax content:

1. Use a spreadsheet (e.g. Google Sheets) to edit the CSV, restricting edits to:
   - `flat_rate`
   - `standard_deduction`
   - the brackets CSV (`up_to`, `rate`).
2. Export the sheet back to CSV and overwrite the files in `src/main/resources`.
3. Run the Gradle commands above to regenerate the JSON.
4. Use `TaxRuleConfigImporter` to push the resulting rules into the `tax_rule` table.

This keeps state income tax policy data maintainable for non-engineering collaborators while preserving a clear, reproducible pipeline into the engine’s tax catalog.

## Employer-specific tax overlays

Beyond statutory rules, the `tax_rule` table supports employer-specific overlays via the `employer_id` column. The config model already exposes this through the optional `employerId` field on `TaxRuleConfig`.

### Defining an overlay

To define an employer-specific rule (for example, a 1% CA state surcharge for a single employer), add a JSON config under `src/main/resources/tax-config`:

```json
{
  "rules": [
    {
      "id": "US_CA_EMP_ACME_SURCHARGE_2025",
      "jurisdictionType": "STATE",
      "jurisdictionCode": "CA",
      "basis": "StateTaxable",
      "ruleType": "FLAT",
      "rate": 0.01,
      "annualWageCapCents": null,
      "brackets": null,
      "standardDeductionCents": null,
      "additionalWithholdingCents": null,
      "employerId": "EMP-ACME",
      "effectiveFrom": "2025-01-01",
      "effectiveTo": "9999-12-31",
      "filingStatus": "SINGLE",
      "residentStateFilter": "CA",
      "workStateFilter": "CA",
      "localityFilter": null
    }
  ]
}
```

Key points:
- `employerId` binds the rule to a specific employer; `null` means “generic statutory” and applies to all employers.
- Jurisdiction (`jurisdictionType`, `jurisdictionCode`) and `basis` should match how the underlying statutory rule is modeled (for example, `STATE`/`CA` on `StateTaxable`).
- Effective dating works the same as for statutory rules.

### Importing overlays

Overlays are imported with the same pipeline as other tax rules. For example, using H2 or Postgres via `TaxRuleConfigImporter`:

1. Place your overlay JSON under `src/main/resources/tax-config`.
2. Point `TaxRuleConfigImporter` at the directory (including both `state-income-YYYY.json` and any overlay files).
3. Run the importer; it will append rows into `tax_rule`, setting `employer_id` based on `employerId` in the config.

At runtime, `JooqTaxRuleRepository` and the H2 test repository both apply the selection rule:
- Rules where `employer_id IS NULL` (generic) **or** `employer_id = query.employerId` are eligible.

This means an employer with overlays sees both statutory and overlay rules, while an employer without overlays sees only statutory rules.
