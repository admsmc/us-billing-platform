#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Restore a single tenant database (pg_restore wrapper).

Env (required):
  DB_NAME      Target database name
  DUMP_FILE    Path to a pg_dump custom-format dump file

Env (optional):
  CREATE_DB    If 'true', attempt to create the DB before restore (default: false)
  DRY_RUN      If 'true', only print commands (default: false)

Connection:
  Uses standard libpq env vars: PGHOST, PGPORT, PGUSER, PGPASSWORD.

Notes:
- This uses --clean --if-exists to replace existing objects.
- In production, restores are platform-specific (PITR, snapshots). This is a dev/staging helper.
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

: "${DB_NAME:?DB_NAME is required}"
: "${DUMP_FILE:?DUMP_FILE is required}"

CREATE_DB=${CREATE_DB:-false}
DRY_RUN=${DRY_RUN:-false}

if [[ "$CREATE_DB" == "true" ]]; then
  create_cmd=(createdb "$DB_NAME")
  if [[ "$DRY_RUN" == "true" ]]; then
    printf '%q ' "${create_cmd[@]}"; echo
  else
    "${create_cmd[@]}" || true
  fi
fi

restore_cmd=(pg_restore --clean --if-exists --no-owner --no-privileges --dbname "$DB_NAME" "$DUMP_FILE")

if [[ "$DRY_RUN" == "true" ]]; then
  printf '%q ' "${restore_cmd[@]}"; echo
  exit 0
fi

"${restore_cmd[@]}"
echo "Restored $DB_NAME from $DUMP_FILE"