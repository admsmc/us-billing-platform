## Summary

## Tax/Labor content change checklist (required if this PR touches tax-content or labor standards)
- Source:
  - Kind (statute/publication/dataset/internal):
  - Source id:
  - Source revision date (YYYY-MM-DD):
  - Source checksum SHA-256 (if applicable):
- Internal approval:
  - SME/compliance approver(s):
  - Approval reference (PR/ticket/doc):
  - Approval date (YYYY-MM-DD):
- Validation:
  - `./scripts/gradlew-java21.sh --no-daemon check`
  - If artifacts are generated from CSV, confirm the “artifacts up-to-date” checks are green:
    - `:tax-service:validateGeneratedTaxArtifacts`
    - `:labor-service:validateGeneratedLaborArtifacts`

## Notes
