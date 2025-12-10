#!/usr/bin/env bash
set -euo pipefail

# Refresh labor standards artifacts (tests + JSON + SQL) from the CSV.
# Usage:
#   LABOR_YEAR=2025 ./scripts/refresh-labor-standards.sh

ROOT_DIR="$(git rev-parse --show-toplevel)"
cd "$ROOT_DIR"

LABOR_YEAR="${LABOR_YEAR:-2025}"

./gradlew :labor-service:test --no-daemon
./gradlew :labor-service:runLaborStandardsImporter --no-daemon -PlaborYear="$LABOR_YEAR"
