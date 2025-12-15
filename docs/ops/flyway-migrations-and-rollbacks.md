# Flyway migrations and rollbacks
This repository uses Flyway for schema migrations.
When `tenancy.mode=DB_PER_EMPLOYER`, each service migrates *every configured tenant database* at startup via `TenantFlywayMigrator` (see `tenancy-core/src/main/kotlin/com/example/uspayroll/tenancy/db/TenantFlywayMigrator.kt`).

## Principles (tier-1 defaults)
- Prefer **expand/contract** migrations (backward compatible) over “big bang” changes.
- Prefer **roll-forward** (hotfix migration) over rollback.
- Treat migration artifacts as **immutable** once released.
- In production, run schema changes in a controlled window with explicit operator visibility.

## Expand/contract patterns
Recommended patterns for application changes that touch schema:
- Additive first: add new columns/tables/indexes (nullable / defaulted) while old code still works.
- Dual write / read switch: write both old+new shapes while reading old; then switch reads; then stop writing old.
- Contract last: drop old columns/tables only after the new version has been stable for at least one full payroll cycle.

## DB-per-employer operational model
With database-per-employer, migrations are *per service* and *per tenant*.

Practical implications:
- A single deploy can touch N tenant databases. Expect variance in latency and failure modes.
- Migrations must be idempotent and safe to re-run.
- Incident response often targets one employer DB (restore + replay) rather than the whole fleet.

## How migrations run today
In `DB_PER_EMPLOYER` mode, services override Spring Boot’s Flyway behavior using a `FlywayMigrationStrategy` to migrate per-tenant DataSources and to avoid running Flyway against the routing DataSource.
Example (HR): `hr-service/src/main/kotlin/com/example/uspayroll/hr/tenancy/HrTenancyConfig.kt`.

## Recommended production workflow
### 1) Pre-flight checks
- Confirm schema change is expand/contract unless you have a compelling reason.
- Confirm new code can run with both:
  - “old schema” (before migration)
  - “new schema” (after migration)
- Confirm the migration is compatible with your production engine (Postgres recommended) and sized for the largest tenants.

### 2) Deploy strategy
- Use a two-phase rollout when possible:
  1) Deploy code that is compatible with the *old* schema.
  2) Apply migrations.
  3) Deploy code that uses the new schema (if needed).

### 3) Observability during migration
During deploy windows, watch:
- service startup logs for `flyway.migrate.tenant ...`
- error rate spikes and readiness failures
- database locks / long-running DDL

## Rollback policy
Flyway Community Edition does not provide automatic “undo” for DDL. For this repo, “rollback” means one of:

### Option A: Roll-forward (preferred)
Create a new migration that fixes the issue while keeping compatibility.

### Option B: Restore and re-deploy (break-glass)
If a migration causes severe production impact and cannot be quickly rolled forward:
- Restore the affected tenant database(s) from backup to a known-good point.
- Re-deploy the last known-good application version.
- Re-apply migrations only after validating a corrected migration in a staging environment.

Notes:
- In DB-per-employer, break-glass restores can often be scoped to a single employer.
- After a restore, ensure application idempotency/replay invariants are respected (see `docs/ops/idempotency-and-replay-invariants.md`).

## Safety notes
- Avoid destructive statements (drop/rename) unless you’ve completed the contract phase.
- Keep each migration small and fast. Prefer online index builds where supported.
- Avoid introducing cross-tenant coupling (do not rely on ordering across tenant DBs).
