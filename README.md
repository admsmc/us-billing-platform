# US Payroll Platform

Initial skeleton for an enterprise-grade, Kotlin-based US payroll system.

## Modules

- `shared-kernel`: shared identifiers and value types.
- `payroll-domain`: pure payroll calculation logic.
- `hr-domain`, `time-domain`, `tax-domain`: core domain models for HR, time, and tax.
- `persistence-core`, `messaging-core`, `web-core`: shared infrastructure code.
- Service modules such as `hr-service`, `payroll-orchestrator-service`, `payroll-worker-service`, etc., host HTTP endpoints and workers.

This is a starting point; more detailed wiring and infrastructure can be added incrementally.
