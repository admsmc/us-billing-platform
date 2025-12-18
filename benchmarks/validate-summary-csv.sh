#!/usr/bin/env bash
# Validates that summary.csv has consistent column counts between header and data rows.
# Usage: ./benchmarks/validate-summary-csv.sh /path/to/summary.csv

set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <summary.csv>" >&2
  exit 1
fi

CSV_FILE="$1"

if [[ ! -f "$CSV_FILE" ]]; then
  echo "Error: File not found: $CSV_FILE" >&2
  exit 1
fi

# Count columns in header (line 1)
header_cols=$(head -n 1 "$CSV_FILE" | awk -F',' '{print NF}')

echo "Header columns: $header_cols"

# Validate each data row
line_num=1
all_valid=true

while IFS= read -r line; do
  line_num=$((line_num + 1))
  
  # Skip empty lines
  if [[ -z "$line" ]]; then
    continue
  fi
  
  cols=$(echo "$line" | awk -F',' '{print NF}')
  
  if [[ $cols -ne $header_cols ]]; then
    echo "ERROR: Line $line_num has $cols columns (expected $header_cols)" >&2
    all_valid=false
  else
    echo "Line $line_num: OK ($cols columns)"
  fi
done < <(tail -n +2 "$CSV_FILE")

if [[ "$all_valid" == "true" ]]; then
  echo ""
  echo "✓ All rows have consistent column counts"
  exit 0
else
  echo ""
  echo "✗ Column count mismatch detected"
  exit 1
fi
