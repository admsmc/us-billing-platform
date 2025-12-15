# Payments provider integration seam
This document describes how `payments-service` integrates with external payment rails (ACH/wire/check) via a provider port, and clarifies sensitive data and encryption boundaries.

## Goals
- Provide a clean integration boundary for bank file generation / payment provider submission.
- Keep the core payroll workflow idempotent and replay-safe.
- Ensure sensitive banking details are never emitted on event streams and are stored/handled only within approved boundaries.

## Key principle: tokenization, not raw bank details
`payments-service` must not ingest or persist raw routing numbers, account numbers, or other bank PII.

Instead:
- Bank details should be stored in a dedicated onboarding/billing subsystem (or a third-party vault/provider).
- `payments-service` should operate on *opaque destination references* (tokens) that can be resolved only by the provider integration layer.

This keeps:
- Kafka payloads free of sensitive data.
- `payments-service` database free of raw account/routing numbers.
- encryption responsibilities explicit and localized.

## Code-level seam
`payments-service` defines a provider port:
- `payments-service/src/main/kotlin/com/example/uspayroll/payments/provider/PaymentProvider.kt`

The scheduled processor (`PaymentsProcessor`) claims batches and delegates submission to the provider.
The provider can optionally return immediate terminal outcomes (used by the sandbox provider). Real providers typically settle asynchronously.

### Sandbox provider
For local/dev/tests, `SandboxPaymentProvider`:
- generates deterministic provider references
- can be configured to fail a payment on the first attempt for test coverage
- can auto-settle immediately

Configuration:
- `payments.provider.type=sandbox`
- `payments.provider.sandbox.auto-settle=true|false`
- `payments.provider.sandbox.fail-if-net-cents-equals=<cents>`

## Persistence model (provider metadata)
To support reconciliation and downstream integrations, payments-service stores provider metadata:

- `payment_batch.provider` (e.g. `SANDBOX`)
- `payment_batch.provider_batch_ref` (provider’s batch/file reference, nullable)
- `paycheck_payment.provider`
- `paycheck_payment.provider_payment_ref` (provider’s payment reference/trace, nullable)

Notes:
- These fields are intended to be *non-sensitive identifiers* suitable for logs, reconciliation dashboards, and support workflows.
- Provider references should never embed raw account/routing numbers.

## Operational expectations for real providers
A real provider integration will typically add:
- asynchronous status updates (webhooks, settlement reports, return files)
- stronger audit trails around submitted files (hashing, storage location, retention)
- encryption and access controls for any provider credentials and for any stored artifacts

Recommended controls:
- credentials stored in a secret manager; never committed
- artifacts stored in a restricted bucket with server-side encryption
- least-privilege IAM for read/write
- explicit data retention policy

## Related reconciliation
- Payment lifecycle status changes are still emitted as `PaycheckPaymentStatusChanged` events.
- Orchestrator can rebuild projections from payments-service as source of truth (see `docs/ops/dlq-replay-reconciliation.md`).
