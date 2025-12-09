# Tax Rule Schema (Candidate)

This document outlines a candidate relational schema for storing US tax rules
that feed the `TaxRuleRecord` model in `tax-service`. The goal is to support a
wide range of taxes:

- Federal income tax (bracketed)
- State income tax (flat or bracketed)
- Local income taxes
- FICA Social Security (flat with annual wage cap)
- FICA Medicare (flat, plus additional bracketed rate above threshold)
- FUTA (employer-only, capped)
- State unemployment / disability insurance (flat with caps)

The engine remains generic: rules are expressed in terms of `TaxRule`,
`TaxBasis`, and `TaxJurisdiction`. This schema focuses on how to persist and
select the correct rules for a given employer, date, and employee context.

## Core Table: `tax_rule`

```sql
CREATE TABLE tax_rule (
    id                          VARCHAR(100) PRIMARY KEY,

    -- Which employer this rule applies to. NULL means "applicable to all"
    -- employers in the jurisdiction (statutory default). A non-null value
    -- represents an employer-specific overlay or surcharge.
    employer_id                 VARCHAR(64)      NULL,

    jurisdiction_type           VARCHAR(20)      NOT NULL, -- 'FEDERAL' | 'STATE' | 'LOCAL' | 'OTHER'
    jurisdiction_code           VARCHAR(32)      NOT NULL, -- e.g. 'US', 'CA', 'NYC', 'US-ER'

    -- Which basis from the payroll engine this rule applies to
    basis                       VARCHAR(32)      NOT NULL, -- enum name for TaxBasis

    -- High-level rule type. Flat covers FICA, FUTA, SDI, etc. Bracketed covers
    -- progressive income taxes and thresholds like Additional Medicare.
    rule_type                   VARCHAR(16)      NOT NULL, -- 'FLAT' | 'BRACKETED'

    -- Flat-rate specific fields
    rate                        DOUBLE PRECISION     NULL, -- e.g. 0.062 for 6.2%
    annual_wage_cap_cents       BIGINT               NULL, -- e.g. 16020000 for $160,200.00

    -- Bracketed-specific fields
    brackets_json               TEXT                 NULL, -- JSON array of { upToCents, rate }
    standard_deduction_cents    BIGINT               NULL,
    additional_withholding_cents BIGINT             NULL,

    -- Effective dating & applicability filters
    effective_from              DATE            NOT NULL,
    effective_to                DATE            NOT NULL, -- use '9999-12-31' for open-ended

    filing_status               VARCHAR(32)     NULL,     -- e.g. 'SINGLE', 'MARRIED', or NULL = all
    resident_state_filter       VARCHAR(2)      NULL,     -- optional constraint by resident state
    work_state_filter           VARCHAR(2)      NULL,     -- optional constraint by work state
    locality_filter             VARCHAR(32)     NULL,     -- e.g. 'NYC', 'SF'

    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_by                  VARCHAR(64)             NOT NULL,
    superseded_by_id            VARCHAR(100)            NULL
);

CREATE INDEX idx_tax_rule_effective
    ON tax_rule (employer_id, jurisdiction_type, jurisdiction_code, effective_from, effective_to);

CREATE INDEX idx_tax_rule_filters
    ON tax_rule (filing_status, resident_state_filter, work_state_filter, locality_filter);
```

## SCD2 / Append-only semantics

The `tax_rule` table is modeled as a slowly changing dimension (Type 2) and is
logically append-only:

- Business changes to tax rules (rates, caps, brackets, applicability) are
  represented by **inserting new rows** with new effective date ranges.
- Existing rows are never updated in place for business fields; they remain as a
  historical record of what was in force.
- A change in law or policy inserts a new row with a new `id`, new
  `effective_from`/`effective_to`, and optionally sets `superseded_by_id` on the
  prior row for traceability.
- Queries for a given `asOfDate` filter rows with `effective_from <= asOfDate <
  effective_to`, so historical rows naturally fall out of the selection.

This ensures that:

- Historical paychecks can always be recomputed using the exact rules that were
  active at the time.
- Audits can reconstruct what rules were in force and when changes occurred.

## Mapping to `TaxRuleRecord`

The `TaxRuleRecord` data class in `tax-service` is designed to be a direct
projection of a `tax_rule` row:

- `id`                           `tax_rule.id`
- `jurisdictionType`            `tax_rule.jurisdiction_type`
- `jurisdictionCode`            `tax_rule.jurisdiction_code`
- `basis`                       `tax_rule.basis`
- `ruleType`                    `tax_rule.rule_type`
- `rate`                        `tax_rule.rate`
- `annualWageCapCents`          `tax_rule.annual_wage_cap_cents`
- `bracketsJson`                `tax_rule.brackets_json`
- `standardDeductionCents`      `tax_rule.standard_deduction_cents`
- `additionalWithholdingCents`  `tax_rule.additional_withholding_cents`
- `employerId`                  `tax_rule.employer_id`
- `effectiveFrom`               `tax_rule.effective_from`
- `effectiveTo`                 `tax_rule.effective_to`
- `filingStatus`                `tax_rule.filing_status`
- `residentStateFilter`         `tax_rule.resident_state_filter`
- `workStateFilter`             `tax_rule.work_state_filter`
- `localityFilter`              `tax_rule.locality_filter`

The `TaxRuleRepository.findRulesFor(query: TaxQuery)` implementation is
responsible for:

- Filtering by `employer_id` (either NULL or matching the employer),
- Enforcing `effective_from <= query.asOfDate < effective_to`,
- Applying any relevant filters from employee context (filing status, resident
  state, work state, locality), and
- Returning only the rows that should be active for the given `TaxQuery`.

## Examples of how common US taxes map into this schema

### Federal income tax

- `jurisdiction_type = 'FEDERAL'`
- `jurisdiction_code = 'US'`
- `basis = 'FederalTaxable'`
- `rule_type = 'BRACKETED'`
- `brackets_json` contains the IRS bracket ranges and rates
- `standard_deduction_cents` holds the per-filing-status standard deduction
- `filing_status` is set per row (for each status), or NULL if encoded in brackets

### FICA Social Security (employee and employer)

Two flat rules (one employee, one employer) sharing the same basis `SocialSecurityWages`:

- `jurisdiction_type = 'FEDERAL'`
- `jurisdiction_code = 'US'`
- `basis = 'SocialSecurityWages'`
- `rule_type = 'FLAT'`
- `rate = 0.062`
- `annual_wage_cap_cents = {year-specific cap}`

Employee vs employer FICA can be distinguished by which list the rule is placed
into inside `TaxContext` (employee vs employer-specific), not by the schema
itself.

### FICA Medicare + Additional Medicare

Base Medicare:

- `basis = 'MedicareWages'`
- `rule_type = 'FLAT'`
- `rate = 0.0145`
- `annual_wage_cap_cents = NULL` (no cap)

Additional Medicare (thresholded):

- `basis = 'MedicareWages'`
- `rule_type = 'BRACKETED'`
- `brackets_json` contains a single bracket where rate = 0.009 above the
  threshold; 0% below.

### FUTA and state unemployment / disability

Typically represented as flat rules with caps:

- `jurisdiction_type = 'FEDERAL'` / `STATE`
- `basis = 'Gross'` or a more specific taxable-wage basis if needed
- `rule_type = 'FLAT'`
- `rate` set to the FUTA/SUI/SDI rate
- `annual_wage_cap_cents` set to the statutory wage base

### State and local income taxes

- Flat states: `rule_type = 'FLAT'`, `basis = 'StateTaxable'`.
- Progressive states: `rule_type = 'BRACKETED'` with appropriate brackets.
- Localities: `jurisdiction_type = 'LOCAL'`, `jurisdiction_code` and
  `locality_filter` help disambiguate overlapping local rules.

## Config-managed tax content and importer

Tax rules are authored and versioned as configuration files in Git (for
example, JSON files under `tax-service/src/main/resources/tax-config`). These
files use the `TaxRuleFile` / `TaxRuleConfig` / `TaxBracketConfig` model
(`tax-service/src/main/kotlin/com/example/uspayroll/tax/config/TaxRuleConfig.kt`).

Example config (simplified):

```json
{
  "rules": [
    {
      "id": "US_FED_FIT_2025_SINGLE",
      "jurisdictionType": "FEDERAL",
      "jurisdictionCode": "US",
      "basis": "FederalTaxable",
      "ruleType": "BRACKETED",
      "brackets": [
        { "upToCents": 1100000, "rate": 0.10 },
        { "upToCents": 4472500, "rate": 0.12 },
        { "upToCents": null, "rate": 0.22 }
      ],
      "standardDeductionCents": 1460000,
      "effectiveFrom": "2025-01-01",
      "effectiveTo": "9999-12-31",
      "filingStatus": "SINGLE"
    }
  ]
}
```

An importer (`TaxRuleConfigImporter` in
`tax-service/src/main/kotlin/com/example/uspayroll/tax/persistence/TaxRuleConfigImporter.kt`)
reads these files using Jackson (`TaxRuleFile`) and inserts corresponding SCD2
rows into the `tax_rule` table via jOOQ:

- For each `TaxRuleConfig`, it serializes `brackets` into `brackets_json`.
- It inserts a new row with all of the configured fields, without updating or
  deleting existing rows.
- Callers can import a single file or all `.json` files from a directory.

This approach makes config files the **authoritative, Git-versioned source of
truth** for tax content, while the database remains the **runtime store** used
by `TaxRuleRepository` / `TaxCatalog` to drive the payroll engine.

## Next steps

- Add migrations or tooling that call `TaxRuleConfigImporter` against a target
  Postgres database during deployment.
- Extend the config model to cover additional jurisdictions and employer-
  specific overlays as needed.
- Add regression tests that load known-good configs into an in-memory Postgres
  (or test schema) and validate engine outputs for canonical scenarios.
