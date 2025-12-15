# Yearly update playbook: labor standards (B2)

This playbook describes how to perform an annual update to labor standards content (minimum wage, tipped wage, OT thresholds), including locality overrides.

## Scope
- Inputs:
  - `labor-service/src/main/resources/labor-standards-<YEAR>.csv`
  - `labor-service/src/main/resources/labor-standards-<YEAR>-local.csv` (optional)
- Generated artifacts:
  - `labor-service/src/main/resources/labor-standards-<YEAR>.json`
  - `labor-service/src/main/resources/labor-standard-<YEAR>.sql`

## Sources of truth
- State labor agency publications and minimum wage bulletins
- Municipal/county ordinances for locality overrides
- Statutory text where applicable

Record sources + approvals in `*.metadata.json` sidecars (see `docs/ops/content-metadata-traceability-standard.md`).

## Pre-flight
- `./scripts/gradlew-java21.sh --no-daemon check`

## Update procedure (per year)

### Step 1 — Update CSV inputs
1. Update:
   - `labor-standards-<YEAR>.csv`
   - `labor-standards-<YEAR>-local.csv` (if needed)
2. Keep schema columns stable (engineering-owned). Policy/ops typically edits only numeric wage columns.

### Step 2 — Regenerate JSON + SQL
From repo root:

```bash
LABOR_YEAR=<YEAR> ./scripts/refresh-labor-standards.sh
```

This will:
- run `:labor-service:test`
- regenerate JSON + SQL
- validate:
  - `:labor-service:validateLaborContentMetadata`
  - `:labor-service:validateGeneratedLaborArtifacts`
- refresh metadata sidecars with updated `artifact.sha256`

### Step 3 — Fill in metadata business fields
Before merging, edit the sidecars and set:
- `source.kind`, `source.id`, `source.revision_date` (and optional `source.checksum_sha256`)
- `approvals[]` (SME/compliance sign-off)

### Step 4 — Review checkpoints
- CODEOWNERS-required reviewers must approve (see `.github/CODEOWNERS`).
- Fill out the PR template section (`.github/pull_request_template.md`).

### Step 5 — Merge + promotion/import steps

#### Deploy-time promotion
Shipping updated labor standards content is done by deploying new service builds containing the updated artifacts.

#### Database import
The generated `labor-standard-<YEAR>.sql` is the canonical insert script for the `labor_standard` table.
Operationally:
- Apply via migration/admin job in the target environment.
- Capture logs/output as the audit trail.
- Record the PR SHA and deployment version in the run log.

## Post-deploy verification
- CI `check` should pass on the merged commit.
- Smoke-test labor-service “effective as-of” responses for a few states/localities.
- Run golden tests that exercise a mix of state + locality wage rules.
