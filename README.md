# US Payroll Platform

Enterprise-focused, Kotlin-based US payroll platform with a functional-core architecture (pure calculation domain) surrounded by service modules.

Status: **active development**. This README reflects repo capabilities as of **2025-12-16**.

## High-level architecture

The system is structured as a functional core (pure payroll domain) surrounded by service modules and infrastructure. See:

- `docs/architecture.md` – overall module layout and boundaries (domain, HR, tax, worker).
- `tax-service/README.md` – state and federal tax configuration pipeline.
- `labor-service/README.md` – state and federal labor standards pipeline.

## Modules

Core:
- `shared-kernel`: shared identifiers and value types.
- `payroll-domain`: pure payroll calculation logic (functional core).

Core services:
- `hr-service`: HR system-of-record for employee profiles and pay periods (effective-dated + audit).
- `tax-service` + `tax-content`: curated tax artifacts, validators, and DB-backed tax catalogs.
- `labor-service`: curated labor standards artifacts, validators, and DB-backed labor standards catalog.
- `payroll-orchestrator-service`: pay run workflows (off-cycle, corrections, retro), outbox/event publishing, and reconciliation.
- `payroll-worker-service`: queue-driven per-employee paycheck computation + workflow execution.
- `payments-service`: payment initiation/status workflows (system-of-record) and integration seam.
- `reporting-service`: downstream reporting projections/consumers.
- `filings-service`: filing “shapes” (941/940/W-2/W-3/state summaries) and reconciliation hooks.

Shared infrastructure:
- `persistence-core`, `messaging-core`, `web-core`, `tenancy-core`: shared building blocks for JDBC, outbox/inbox, HTTP, and multi-tenant patterns.

## Current limitations (as of 2025-12-16)

This repository has working end-to-end workflows and production-minded infrastructure, but it is not yet a complete enterprise production system. Key limitations:

- **Year coverage**: primary focus is **2025** tax and labor rules. Prior/future years and multi-year backtesting are limited.
- **Coverage breadth**: federal + selected state/local scenarios are covered via curated artifacts and golden tests, but nationwide/local edge-case completeness is still in progress.
- **Security**: service-to-service and external authentication/authorization are not yet fully wired for production (see `identity-service`/`edge-service`).
- **Tenancy/isolation**: a full production multi-tenant isolation strategy (and automated enforcement) is not finalized.
- **Compliance & data protection**: additional work is needed for enterprise-grade PII policies, retention, key management/rotation, and DR/restore procedures.

## Ops & deployment notes

- Kubernetes manifests (Kustomize) live under `deploy/k8s/`.
- Operational runbooks, SLO guidance, and golden test strategy live under `docs/ops/`.

For the current enterprise-readiness capability register (Done/Partial/Not Started) and rationale, see:
- `docs/ops/enterprise-readiness-capability-backlog.md`
- `docs/ops/golden-tests-enterprise-readiness.md`
- `docs/ops/production-deployment-hardening.md`
