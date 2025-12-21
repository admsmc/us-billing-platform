# Backup/restore and DR drills (DB-per-employer)
This document defines a baseline operational approach for backups and DR testing.

## Objectives
- Limit blast radius: restore should be possible for a single tenant (employer) without impacting others.
- Prove recoverability via periodic drills.

## Backup strategy
For each service database (HR, Orchestrator, Tax, Labor, Payments, Reporting/Filings as applicable):
- Automated daily full backups (or PITR where available).
- Backups encrypted.
- Backup retention aligned with policy (see `docs/ops/retention-export-delete.md`).

## Restore procedure (single tenant)
1) Identify tenant/employer and affected bounded context.
2) Restore the tenant DB into an isolated environment.
3) Run migrations to current version (if needed).
4) Run reconciliation/projection rebuild tools where applicable.
5) Validate key invariants:
   - payrun/paycheck counts match
   - payment status projections consistent
   - ledger/event streams can be replayed safely

Executable drill artifact (compose-first):
- `scripts/ops/drills/single-tenant-restore-validate-compose.sh`

Notes:
- This is a dev/staging drill helper that uses `pg_dump`/`pg_restore` inside the compose Postgres container.
- Production restores are platform-specific (snapshots/PITR), but the invariant checks and reporting format are reusable.

## DR drills
Suggested cadence:
- Quarterly: restore one randomly selected tenant into a test environment and validate invariants.
- Annually: full region failover simulation (platform-dependent).

## Evidence artifacts
A drill should produce:
- timestamp
- tenants restored
- RPO/RTO achieved
- validation checklist results
- follow-up actions
