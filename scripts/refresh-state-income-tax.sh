#!/usr/bin/env bash
set -euo pipefail

# Refresh state income tax artifacts + metadata.
# Usage:
#   TAX_YEAR=2025 ./scripts/refresh-state-income-tax.sh

ROOT_DIR="$(git rev-parse --show-toplevel)"
cd "$ROOT_DIR"

TAX_YEAR="${TAX_YEAR:-2025}"
REVISION_DATE="${REVISION_DATE:-$(date +%F)}"

./gradlew :tax-service:test --no-daemon
./gradlew :tax-service:runStateIncomeTaxImporter --no-daemon -PtaxYear="$TAX_YEAR"

# Validate tax config + content metadata + generated-artifact up-to-date checks.
./gradlew :tax-service:validateTaxConfig --no-daemon
./gradlew :tax-service:validateTaxContentMetadata --no-daemon
./gradlew :tax-service:validateGeneratedTaxArtifacts --no-daemon -PtaxYear="$TAX_YEAR"

# Refresh metadata sidecars (sha256) for curated tax content.
./scripts/ops/generate-content-metadata.py \
  --root . \
  --domain tax \
  --revision-date "$REVISION_DATE" \
  --glob 'tax-content/src/main/resources/tax-config/*.json' \
  --glob 'tax-content/src/main/resources/*.csv'
