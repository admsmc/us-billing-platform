# Database-per-employer tenancy
This repository’s target tenant isolation model is database-per-employer.

In this model, each employer (tenant) has its own database per bounded context/service:
- HR DB per employer
- Payroll orchestrator DB per employer
- Tax DB per employer
- Labor DB per employer
- Payments DB per employer

This provides the strongest isolation boundary because cross-tenant reads/writes are prevented by the database topology, not just application logic.

## Runtime routing
Services that access a database run with a routing DataSource (Spring `AbstractRoutingDataSource`).
The routing key is the employer external ID.

Request-scoped tenant resolution:
- For HTTP endpoints under `/employers/{employerId}/...`, the path variable `employerId` is the source of truth.
- If the gateway/edge also propagates `X-Employer-Id`, it must match the path value.

## Configuration
Tenancy can be enabled by setting:
- `tenancy.mode=DB_PER_EMPLOYER`
- `tenancy.databases.<EMPLOYER_ID>.url=...`
- `tenancy.databases.<EMPLOYER_ID>.username=...`
- `tenancy.databases.<EMPLOYER_ID>.password=...`

Notes:
- All configured tenant databases for a given service must use the same engine/dialect (e.g. all Postgres).
- Tenant databases are expected to be pre-provisioned (or provisioned by an external tool/service) before the application starts.

## Migrations
When `tenancy.mode=DB_PER_EMPLOYER`, Flyway migrations must run once per tenant database.
The current implementation uses a Spring Boot `FlywayMigrationStrategy` to run per-tenant migrations.

## Operational implications
Database-per-employer is operationally heavy:
- Provisioning: create DB, user/role, credentials per tenant
- Migrations: run per tenant
- Backups/restore: per tenant
- Observability: per tenant

For a tier-1 offering, plan for automation:
- a provisioning pipeline
- secrets management integration
- migration orchestration and safety controls
- incident playbooks (restore for a single tenant)

## Repo-local ops automation (dev/staging helpers)
This repo includes scripts under `scripts/ops/` to make common tenancy operations repeatable:
- Provision per-tenant DBs/roles: `scripts/ops/tenancy-provision.sh`
- Run migrations per tenant: `scripts/ops/tenancy-migrate.sh`
- Backup a single tenant DB: `scripts/ops/tenancy-backup.sh`
- Restore a single tenant DB: `scripts/ops/tenancy-restore.sh`

These are intended as a baseline; production deployments should integrate with platform-native tooling (managed Postgres snapshots/PITR, secrets managers, etc.).
