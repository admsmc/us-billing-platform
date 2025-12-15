#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Backup a single tenant database (pg_dump wrapper).

Env (required):
  DB_NAME     Database name to dump

Env (optional):
  OUT_DIR     Output directory (default: /tmp/us-payroll-tenant-backups)
  DRY_RUN     If 'true', only print the pg_dump command (default: false)

Connection:
  Uses standard libpq env vars: PGHOST, PGPORT, PGUSER, PGPASSWORD.

Output:
  Writes a custom-format dump: <OUT_DIR>/<DB_NAME>-<timestamp>.dump
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

: "${DB_NAME:?DB_NAME is required}"

OUT_DIR=${OUT_DIR:-/tmp/us-payroll-tenant-backups}
DRY_RUN=${DRY_RUN:-false}

mkdir -p "$OUT_DIR"

ts=$(date -u +%Y%m%dT%H%M%SZ)
out_file="${OUT_DIR}/${DB_NAME}-${ts}.dump"

cmd=(pg_dump --format=custom --no-owner --no-privileges --file "$out_file" "$DB_NAME")

if [[ "$DRY_RUN" == "true" ]]; then
  printf '%q ' "${cmd[@]}"; echo
  exit 0
fi

"${cmd[@]}"
echo "Wrote backup: $out_file"