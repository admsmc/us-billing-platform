# PII handling and retention (baseline)
Payroll systems process high-risk data (PII, compensation, tax identifiers, garnishments). This document defines the baseline expectations for tier-1 posture in this repo.

## Scope of sensitive data
Examples (non-exhaustive):
- Employee identifiers (internal + external)
- Names, addresses, phone/email
- Compensation amounts
- Garnishment orders and withholding events
- Tax elections and filing status

## Data minimization
- Do not store data you do not need to compute payroll or meet a compliance requirement.
- Prefer immutable, effective-dated facts over duplicative copies.

## Logging
- Treat employee-level data as PII by default.
- Logs should not contain:
  - SSNs, bank account numbers, full addresses
  - raw documents uploaded by users
- When logging is necessary for operational support:
  - log stable IDs (payRunId, paycheckId, employerId)
  - avoid employee names; prefer employeeId

## Encryption
This repo assumes encryption is handled by platform controls:
- encryption at rest for databases and backups
- encryption in transit (TLS) between clients/services

For database-per-employer, ensure every tenant DB:
- has encrypted storage
- has backups encrypted with separate keys where required

## Retention
Retention is organization/policy dependent, but a tier-1 system needs:
- a documented retention policy for payroll artifacts (paychecks, payruns, payments, tax/labor configs)
- the ability to export a tenant’s data
- deletion workflows where legally permitted

## Access controls
- All external requests must be authenticated and authorized at ingress.
- Internal admin/ops endpoints must be protected (at minimum shared-secret; target mTLS/JWT).

## Auditability
At minimum, record who/what/when for:
- configuration changes
- payrun approvals
- payment initiation
- manual replays (DLQ, reprocessing)
