# Yearly update playbook: state income tax (B2)

This playbook describes how to perform an annual update to state income tax withholding content.

It is written to be reproducible by engineering with review checkpoints for SME/compliance.

## Scope
- Inputs:
  - `tax-content/src/main/resources/state-income-tax-<YEAR>-rules.csv`
  - `tax-content/src/main/resources/state-income-tax-<YEAR>-brackets.csv`
- Generated artifact:
  - `tax-content/src/main/resources/tax-config/state-income-<YEAR>.json`

## Sources of truth
- Each state’s Department of Revenue/Taxation publication for the tax year
- Statutory text where applicable

Record the sources in the `*.metadata.json` sidecar files (see `docs/ops/content-metadata-traceability-standard.md`).

## Pre-flight
1. Create a working branch.
2. Confirm tooling is green before starting:
   - `./scripts/gradlew-java21.sh --no-daemon check`

## Update procedure (per year)

### Step 1 — Update CSV inputs
1. Edit:
   - `state-income-tax-<YEAR>-rules.csv`
   - `state-income-tax-<YEAR>-brackets.csv`
2. Prefer spreadsheet workflow for numeric fields.
3. Keep IDs and schema fields consistent (engineering-owned).

### Step 2 — Regenerate JSON (CSV -> TaxRuleFile)
From repo root:

```bash
TAX_YEAR=<YEAR> ./scripts/refresh-state-income-tax.sh
```

This will:
- run `:tax-service:test`
- regenerate `tax-config/state-income-<YEAR>.json`
- validate:
  - `:tax-service:validateTaxConfig`
  - `:tax-service:validateTaxContentMetadata`
  - `:tax-service:validateGeneratedTaxArtifacts`
- refresh metadata sidecars with updated `artifact.sha256`

### Step 3 — Fill in metadata business fields
The metadata generator produces placeholders for business fields.
Before merging:
1. Edit the relevant sidecars (at minimum the CSV inputs and the generated JSON) and set:
   - `source.kind`
   - `source.id`
   - `source.revision_date`
   - `source.checksum_sha256` (if you have a source PDF/image to checksum)
   - `approvals[]` (SME/compliance sign-off)

### Step 4 — Review checkpoints
- Ensure CODEOWNERS-required reviewers approve the PR (see `.github/CODEOWNERS`).
- Fill out the PR template section (`.github/pull_request_template.md`).

### Step 5 — Merge + promotion/import steps

#### Deploy-time promotion
Shipping new tax content is done by deploying new service builds containing the updated artifacts.

#### Database import
Tax rule rows are inserted append-only into `tax_rule` via `TaxRuleConfigImporter` (`tax-impl`).
The importer logs rule counts and validation outcomes; capture these logs as the operational audit trail.

Recommended operational practice:
- Run the import as a controlled job (migration/admin task) with log retention.
- Record the PR SHA and deployment version in the change ticket / run log.

## Post-deploy verification
- Re-run `./scripts/gradlew-java21.sh --no-daemon check` on the merged commit (CI does this).
- Smoke-test tax-service against an environment with the updated `tax_rule` catalog.
- Run golden tests that cover representative states for the updated year.
