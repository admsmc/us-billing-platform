# Privileged operations (enterprise posture)
This document defines what this repo considers a privileged operation and what must be recorded for auditability.

## Definition
A privileged operation is any action that can:
- move money
- approve/commit payroll results
- modify configuration that affects calculations (tax/labor/config)
- access or mutate sensitive employee/paycheck artifacts
- perform operational break-glass actions (replay, restore, reprocess)

## Required audit signal
For each privileged operation, services should emit a `security_audit` event with:
- `event=privileged_op`
- `outcome=granted|failed`
- `component` (service name)
- `operation` (stable operation key)
- `method`, `path`
- `status`
- `correlationId`
- `employerId` (when applicable)
- `principalSub` (when available)
- `reason` (for failures only)

Implementation hook:
- `web-core/src/main/kotlin/com/example/uspayroll/web/security/SecurityAuditLogger.kt`

## Initial catalog (first pass)
The following are considered privileged for audit purposes. This list is intentionally conservative and should be extended as the product surface grows.

### Payroll
- Approve a payrun
  - operation: `payroll_approve`
- Trigger payment initiation / disbursement
  - operation: `payroll_payments_initiate`
- Replay or reconcile DLQ / job failures
  - operation: `payroll_replay`

### Configuration
- Change tax configuration/rules
  - operation: `tax_config_write`
- Change earning/deduction configuration
  - operation: `payroll_config_write`

### Operations
- Execute break-glass access across tenants
  - operation: `break_glass`

## Review expectations
At minimum:
- Break-glass usage should be reviewed periodically.
- Privileged operation failures should be monitored for anomaly detection (brute force / abuse).