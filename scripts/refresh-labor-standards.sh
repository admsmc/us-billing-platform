#!/usr/bin/env bash
set -euo pipefail

# Refresh labor standards artifacts (tests + JSON + SQL) from the CSV.
# Usage:
#   LABOR_YEAR=2025 ./scripts/refresh-labor-standards.sh

ROOT_DIR="$(git rev-parse --show-toplevel)"
cd "$ROOT_DIR"

LABOR_YEAR="${LABOR_YEAR:-2025}"
REVISION_DATE="${REVISION_DATE:-$(date +%F)}"

./gradlew :labor-service:test --no-daemon
./gradlew :labor-service:runLaborStandardsImporter --no-daemon -PlaborYear="$LABOR_YEAR"

# Validate metadata + generated artifacts are up to date.
./gradlew :labor-service:validateLaborContentMetadata --no-daemon
./gradlew :labor-service:validateGeneratedLaborArtifacts --no-daemon -PlaborYear="$LABOR_YEAR"

# Refresh metadata sidecars (sha256) for curated labor content.
./scripts/ops/generate-content-metadata.py \
  --root . \
  --domain labor \
  --revision-date "$REVISION_DATE" \
  --glob "labor-service/src/main/resources/labor-standards-$LABOR_YEAR*.csv" \
  --glob "labor-service/src/main/resources/labor-standards-$LABOR_YEAR.json" \
  --glob "labor-service/src/main/resources/labor-standard-$LABOR_YEAR.sql"
