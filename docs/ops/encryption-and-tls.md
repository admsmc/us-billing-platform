# Encryption and TLS (production expectations)
This repo assumes encryption is enforced by platform controls, but production deployments must be explicit about the posture.

## In transit (TLS)
Required:
- Edge ingress uses TLS (Ingress/LoadBalancer termination).
- Service-to-service traffic uses TLS.
  - Recommended: service mesh mTLS (SPIFFE/SPIRE or platform-native).
  - Alternative: internal-only networks plus TLS termination at sidecars.

Messaging:
- Kafka: TLS + SASL where available; ACLs per service.
- RabbitMQ: TLS + per-service users.

Databases:
- Postgres connections must use TLS (prefer server-side enforced TLS).

## At rest
Required:
- DB storage encryption (cloud KMS-managed).
- Encrypted backups.

## Key management
- Keys must be managed by a KMS/HSM.
- Rotation must be routine and tested.

## Application-level encryption
Where required (e.g. bank routing/account), consider application-level encryption so DB operators cannot read plaintext.

## Verification
- Production deployment checklists should include:
  - TLS endpoints validated
  - Certificates managed/rotated
  - DB parameter groups enforce TLS
  - Messaging endpoints require auth

## Security boundary contract (what the app enforces vs what the platform enforces)
This repository is designed to be secure even in “no mesh” environments, but it assumes certain controls are provided by the platform.

Application-enforced controls (repo-owned):
- Request authentication and authorization at the edge (JWT/OIDC/HS256 as configured).
- Internal operational endpoint protection (internal JWT where enabled).
- Tenant/identity propagation via request headers and correlation IDs.
- PII-safe logging and error response rules.

Platform-enforced controls (infrastructure-owned):
- TLS termination at ingress.
- Service-to-service TLS/mTLS (recommended: service mesh), including cert issuance and rotation.
- Network segmentation / NetworkPolicies (default-deny + explicit allow lists).
- Secret management, storage encryption, and rotation primitives (KMS/HSM backed).

Related docs:
- Secrets and internal auth: `docs/ops/secrets-and-configuration.md`
- PII handling: `docs/ops/pii-classification-and-logging.md`
