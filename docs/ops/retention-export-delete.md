# Retention, export, and deletion (policy template)
Payroll retention is legal/policy dependent. This document is a template for defining a production policy and the minimum engineering hooks required.

## Retention categories
Suggested categories:
- Payroll artifacts: payruns, paychecks, paycheck audits
- Payments artifacts: payment batches, payment statuses, provider metadata
- HR facts: employee profile effective-dated history, compensation history, audit events
- Tax/labor content: curated artifacts and metadata sidecars

## Suggested defaults (examples only)
- Paychecks/payruns/audit logs: 7+ years (typical payroll retention expectations)
- Payments provider metadata: 7+ years
- Operational logs/traces: 14–30 days (depending on policy)

## Required capabilities
1) Tenant export
- Ability to export a tenant’s data (per bounded context) in a deterministic format.
- Export must include audit/provenance.

2) Tenant deletion (where legally permitted)
- Ability to delete tenant data for:
  - test tenants
  - customers in jurisdictions where deletion is permitted
- If deletion is not permitted for payroll artifacts, support logical deletion with legal hold.

3) Legal hold
- Ability to suspend deletion for a tenant when required.

## Implementation notes for this repo
Given the DB-per-employer model (`docs/tenancy-db-per-employer.md`):
- Export/delete can be executed per tenant database.
- Restore can be scoped to a single tenant without cross-tenant blast radius.

## Executable retention hooks (scaffolding)
This repo includes a generic per-tenant SQL retention runner that supports dry-run and apply modes.

Touchpoint:
- `tenancy-core/src/main/kotlin/com/example/uspayroll/tenancy/ops/TenantRetentionRunner.kt`

Intended usage:
- Each service defines a small set of retention rules for *operational* tables (example: outbox rows that are already published and older than a configured window).
- A scheduled job (Kubernetes CronJob / platform scheduler) invokes the retention runner across tenant DBs.

Important:
- Payroll artifacts (payruns/paychecks/audits) often have multi-year retention requirements; do not delete them without an explicit policy decision.
- Prefer starting with low-risk operational data such as outbox rows and transient job state.
