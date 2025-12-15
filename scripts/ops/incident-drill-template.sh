#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Generate a lightweight incident drill artifact (template).

Env (optional):
  OUT_DIR       Output directory (default: /tmp/us-payroll-incident-drills)
  EMPLOYER_ID   Tenant/employer targeted by the drill (default: EMP-DEMO)
  SCENARIO      Short scenario label (default: dlq-growth)

Output:
  Writes a JSON file containing timestamps and placeholders for evidence links.

Example:
  EMPLOYER_ID=EMP1 SCENARIO=payments-stuck ./scripts/ops/incident-drill-template.sh
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

OUT_DIR=${OUT_DIR:-/tmp/us-payroll-incident-drills}
EMPLOYER_ID=${EMPLOYER_ID:-EMP-DEMO}
SCENARIO=${SCENARIO:-dlq-growth}

mkdir -p "$OUT_DIR"

started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
id=$(date -u +%Y%m%dT%H%M%SZ)-${SCENARIO}

file="${OUT_DIR}/incident-drill-${id}.json"

cat >"$file" <<EOF
{
  "incidentId": "${id}",
  "startedAt": "${started_at}",
  "employerId": "${EMPLOYER_ID}",
  "scenario": "${SCENARIO}",
  "hypothesis": "",
  "actions": [
    {
      "timestamp": "${started_at}",
      "action": "triage",
      "notes": ""
    }
  ],
  "evidence": {
    "correlationIds": [],
    "traceIds": [],
    "dashboards": [],
    "logs": []
  },
  "outcome": {
    "resolvedAt": null,
    "rpo": null,
    "rto": null,
    "notes": ""
  }
}
EOF

echo "Wrote incident drill template: $file"