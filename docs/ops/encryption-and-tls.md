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
