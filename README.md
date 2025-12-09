# US Payroll Platform

Initial skeleton for an enterprise-grade, Kotlin-based US payroll system.

## High-level architecture

The system is structured as a functional core (pure payroll domain) surrounded by service modules and infrastructure. See:

- `docs/architecture.md` – overall module layout and boundaries (domain, HR, tax, worker).
- `tax-service/README.md` – state and federal tax configuration pipeline.
- `labor-service/README.md` – state and federal labor standards pipeline.

## Modules

- `shared-kernel`: shared identifiers and value types.
- `payroll-domain`: pure payroll calculation logic.
- `hr-service`: owns employee and pay period data (ports and future service implementation).
- `tax-service`: tax rules/statutory data and DB-backed tax catalogs.
- `labor-service`: labor standards (minimum wage, tipped wage, overtime thresholds).
- `persistence-core`, `messaging-core`, `web-core`: shared infrastructure code.
- `payroll-worker-service`: orchestrates payroll runs against the domain and service ports.

## Current limitations

This repository is an active work-in-progress prototype. Notable limitations today:

- **Year coverage**: primary focus is 2025 tax and labor rules; prior and future years are not yet modeled.
- **HR-service**: HR-service ports are defined, but real persistence and HTTP APIs are not yet implemented.
- **Tax coverage**: federal and state income tax coverage is focused on core scenarios; local taxes and all edge cases are not yet complete.
- **Security**: no authentication/authorization layer is wired at service boundaries.
- **Tenancy**: multi-tenant isolation strategy (per-employer schemas vs row-level security) is not finalized.

These gaps are being addressed incrementally in the enterprise-grade roadmap under `docs/`.
