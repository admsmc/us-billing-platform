#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

IN_MD="$ROOT_DIR/docs/product-spec.md"
OUT_PDF="$ROOT_DIR/docs/product-spec.pdf"

# Directory for rendered Mermaid images (gitignored if build/ is ignored).
export MERMAID_OUT_DIR="$ROOT_DIR/build/mermaid"
export MERMAID_SCALE="2"

mkdir -p "$MERMAID_OUT_DIR"

pandoc "$IN_MD" \
  --from gfm \
  --lua-filter "$ROOT_DIR/scripts/pandoc/mermaid.lua" \
  --pdf-engine=xelatex \
  --toc \
  --number-sections \
  -V geometry:margin=1in \
  -V linkcolor=blue \
  -V urlcolor=blue \
  -o "$OUT_PDF"

echo "Wrote $OUT_PDF"