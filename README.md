# US Billing Platform

**Forked from us-payroll-platform (commit a07abf4) on 2025-12-27**

Enterprise-focused, Kotlin-based utility billing platform with a functional-core architecture (pure calculation domain) surrounded by service modules.

This platform adapts the proven architecture from us-payroll-platform for utility customer information management and billing cycles.

Status: **initial fork - in active development**. Refactoring from payroll domain to billing domain in progress.

## Refactoring Status

**Phase 1: Initial Fork** ✅ Complete
- Forked from us-payroll-platform with full git history preserved
- Renamed root project to us-billing-platform

**Phase 2: Core Renames** 🚧 In Progress
- Package names: `com.example.uspayroll` → `com.example.usbilling`
- Identifiers: `EmployerId` → `UtilityId`, `EmployeeId` → `CustomerId`
- Modules: `payroll-*` → `billing-*`, `hr-service` → `customer-service`

**Phase 3: Domain Replacement** ⏳ Planned
- Replace `payroll-domain` with `billing-domain`
- Meter reading models, usage calculation, rate engine

## Target Architecture

Core:
- `shared-kernel`: shared identifiers (UtilityId, CustomerId, MeterId, BillId) and value types.
- `billing-domain`: pure billing calculation logic (usage → charges → bill totals).

Core services:
- `customer-service`: Customer Information System (CIS) for accounts and meters (effective-dated + audit).
- `rate-service`: Rate tariff catalog (tiered rates, time-of-use, regulatory fees).
- `regulatory-service`: Regulatory compliance rules and PUC reporting requirements.
- `billing-orchestrator-service`: Billing cycle workflows (regular cycles, rebills, corrections).
- `billing-worker-service`: Queue-driven per-customer bill computation.
- `payments-service`: Payment processing and allocation (reused from payroll).
- `reporting-service`: Usage analytics and billing reports.
- `filings-service`: Regulatory reporting (PUC filings, compliance reports).

Shared infrastructure (reused from payroll):
- `persistence-core`, `messaging-core`, `web-core`, `tenancy-core`: shared building blocks for JDBC, outbox/inbox, HTTP, and multi-tenant patterns.

## Current limitations (as of 2025-12-16)

This repository has working end-to-end workflows and production-minded infrastructure, but it is not yet a complete enterprise production system. Key limitations:

- **Year coverage**: primary focus is **2025** tax and labor rules. Prior/future years and multi-year backtesting are limited.
- **Coverage breadth**: federal + selected state/local scenarios are covered via curated artifacts and golden tests, but nationwide/local edge-case completeness is still in progress.
- **Security**: service-to-service and external authentication/authorization are not yet fully wired for production (see `edge-service`).
- **Tenancy/isolation**: a full production multi-tenant isolation strategy (and automated enforcement) is not finalized.
- **Compliance & data protection**: additional work is needed for enterprise-grade PII policies, retention, key management/rotation, and DR/restore procedures.

Note: `identity-service`, `integrations-service`, and `notification-service` directories exist as placeholders but are intentionally not included as Gradle modules until there is a concrete feature to implement.

## Ops & deployment notes

- Kubernetes manifests (Kustomize) live under `deploy/k8s/`.
- Operational runbooks, SLO guidance, and golden test strategy live under `docs/ops/`.

For the current enterprise-readiness capability register (Done/Partial/Not Started) and rationale, see:
- `docs/ops/enterprise-readiness-capability-backlog.md`
- `docs/ops/golden-tests-enterprise-readiness.md`
- `docs/ops/production-deployment-hardening.md`
