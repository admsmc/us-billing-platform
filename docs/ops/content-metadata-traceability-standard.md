# Content metadata + traceability standard (B0)

This document defines a repository-wide standard for capturing metadata about curated content artifacts (CSV/JSON/SQL) used by payroll-critical services.

## Goals
- Make every curated artifact traceable to its source(s) and an internal approval.
- Keep metadata versioned in Git so every change is traceable to a commit SHA.
- Provide deterministic validation so CI can fail if metadata is missing or stale.

## Scope
This applies to:
- Tax curated artifacts under `tax-content/src/main/resources` (CSV inputs and `tax-config/*.json`).
- Labor curated artifacts under `labor-service/src/main/resources` (labor standards CSV + generated JSON/SQL).

## Metadata file convention
For an artifact file:
- `some-file.json` => `some-file.json.metadata.json`
- `some-file.csv` => `some-file.csv.metadata.json`
- `some-file.sql` => `some-file.sql.metadata.json`

The metadata file MUST live alongside the artifact.

## Metadata JSON schema (v1)
`*.metadata.json` must conform to:
- `schemaVersion` (number) – currently `1`
- `contentId` (string) – stable identifier for the artifact
- `domain` (string) – `tax` or `labor`
- `artifact` (object)
  - `path` (string) – repo-relative path to the artifact
  - `sha256` (string) – SHA-256 of the artifact contents
  - `media_type` (string) – e.g. `application/json`, `text/csv`
- `coverage` (object)
  - `year` (number|null)
  - `effective_from` (string|null) – `YYYY-MM-DD`
  - `effective_to` (string|null) – `YYYY-MM-DD`
  - `jurisdictions` (array|null) – optional list of jurisdiction codes
- `source` (object)
  - `kind` (string) – source classification (statute/publication/dataset/internal)
  - `id` (string) – stable reference identifier (e.g. `IRS_PUB_15T_2025`, `MI_TREASURY_WITHHOLDING_2025`, etc.)
    - Must not be a placeholder value like `TBD`.
    - Recommended convention for internally-curated artifacts: `SRC_<contentId>`.
  - `revision_date` (string) – `YYYY-MM-DD` when the source was last refreshed/confirmed
  - `checksum_sha256` (string|null) – checksum of source material (where applicable)
- `approvals` (array)
  - Each approval must include:
    - `role` (string) – e.g. `SME`, `COMPLIANCE`, `ENGINEERING`
    - `reference` (string) – PR, ticket, or document reference for the approval
    - `approved_at` (string) – `YYYY-MM-DD`
    - `name` (string|null)

## Tooling
A generator script exists to create or refresh sidecar metadata:
- `scripts/ops/generate-content-metadata.py`

Validation is enforced via Gradle verification tasks (hooked into `check`) so CI fails when:
- a curated artifact is missing a `*.metadata.json`
- metadata `artifact.sha256` does not match the current artifact contents

## Notes
- Git provides the authoritative commit SHA traceability: changes to either the artifact or its metadata are always associated with a commit.
- The metadata files capture business context (source + approvals), while Git captures when/how the repo changed.
