# PII classification and logging (enterprise posture)
This document defines practical rules for handling sensitive data (PII/PHI/financial) in services and logs.

It complements:
- `docs/pii-and-retention.md`

## Data classes
Treat these as sensitive by default:
- Employee personal data: names, addresses, email/phone
- Government identifiers: SSN/ITIN, tax IDs
- Bank details: account/routing numbers
- Compensation and deductions (pay rates, net pay)
- Garnishments and support orders

## Logging rules
1) Do not log raw PII
- Never log: SSN/ITIN, bank account/routing, full addresses, uploaded documents.
- Avoid employee names; prefer stable IDs (`employeeId`, `paycheckId`, `payRunId`).

2) Use correlation + identity headers
- Rely on:
  - `X-Correlation-ID`
  - `X-Employer-Id`
  - `X-Principal-Sub`

3) Structured logs only
- Prefer JSON logs with stable keys.
- Use a single-line log per request (see `web-core` request logging helpers).

4) PII-safe error responses
- ProblemDetails payloads must not include PII.
- Errors should refer to IDs and generic causes.

## Data access auditing
For production, log/emit audit events for:
- Authentication failures
- Authorization denials
- Privileged operations (success + failure), including:
  - Admin/config changes
  - Payrun approvals
  - Payment initiation
  - Manual replay / operational actions

See also:
- `docs/security-privileged-ops.md`

## Code review checklist
- Does this change introduce any new fields that may contain PII?
- Do logs include any raw payloads?
- Are error messages safe for customer support tickets?
