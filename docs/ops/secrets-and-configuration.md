# Secrets and configuration (production baseline)
This document lists the minimum set of secrets/config required to run the platform in a production-like environment.

Principles:
- Secrets must come from a secret manager (not committed to Git).
- Prefer per-service credentials (least privilege).
- Rotate secrets regularly; treat rotation as a routine operational workflow.

## Edge-service (ingress)
### OIDC/JWT validation
Recommended production mode is OIDC.
Required configuration:
- `EDGE_AUTH_MODE=OIDC`
- `EDGE_AUTH_ISSUER_URI=<https://issuer.example.com/>` (preferred)
  - or `EDGE_AUTH_JWK_SET_URI=<https://issuer.example.com/.well-known/jwks.json>`

Local/dev alternative (not for production):
- `EDGE_AUTH_MODE=HS256`
- `EDGE_AUTH_HS256_SECRET=<dev secret>`

### Required JWT claims/scopes
This repo expects:
- `scope` (space-delimited) and/or `scp` (array)
  - `payroll:read`
  - `payroll:write`
  - `payroll:admin` (umbrella)
- Employer scoping:
  - `employer_id` (single employer) and/or
  - `employer_ids` (array of employers)
  - Optional break-glass:
    - `platform_admin=true`

## Internal service-to-service auth
Internal operational endpoints are protected and should never be exposed publicly.

mTLS remains an infrastructure concern (service mesh / SPIFFE), but this repo supports an application-level internal JWT (HS256) option so service-to-service auth can be production-grade even without a mesh.

### Orchestrator internal endpoints
Orchestrator internal endpoints (e.g. `/payruns/internal/**`) accept either:

1) **Preferred**: short-lived internal JWT (HS256)
- `orchestrator.internal-auth.jwt-shared-secret=<random>`
- `orchestrator.internal-auth.jwt-issuer=us-payroll-platform` (default)
- `orchestrator.internal-auth.jwt-audience=payroll-orchestrator-service` (default)
- Request header:
  - `Authorization: Bearer <internal-jwt>`

2) **Fallback**: shared secret header
- `orchestrator.internal-auth.shared-secret=<random>`
- `orchestrator.internal-auth.header-name=X-Internal-Token` (default)

### Worker → orchestrator internal client auth
The worker client prefers internal JWT when configured:
- `orchestrator.internal-jwt-secret=<random>`
- `orchestrator.internal-jwt-issuer=us-payroll-platform` (default)
- `orchestrator.internal-jwt-audience=payroll-orchestrator-service` (default)
- `orchestrator.internal-jwt-subject=payroll-worker-service` (default)
- `orchestrator.internal-jwt-ttl-seconds=60` (default)

If the internal JWT secret is not set, it falls back to the shared secret:
- `orchestrator.internal-token=<random>`
- `orchestrator.internal-token-header=X-Internal-Token` (default)

### Worker internal endpoints
Worker internal endpoints (e.g. DLQ replay) require:
- `worker.internal-auth.shared-secret=<random>`
- `worker.internal-auth.header-name=X-Internal-Token` (default)

## Databases (per service)
In production, supply DB credentials via secrets.
Typical required values:
- HR: `HR_DB_URL`, `HR_DB_USERNAME`, `HR_DB_PASSWORD`
- Tax: `TAX_DB_URL`, `TAX_DB_USERNAME`, `TAX_DB_PASSWORD`
- Labor: `LABOR_DB_URL`, `LABOR_DB_USERNAME`, `LABOR_DB_PASSWORD`
- Orchestrator: `ORCH_DB_URL`, `ORCH_DB_USERNAME`, `ORCH_DB_PASSWORD`
- Payments: `PAYMENTS_DB_URL`, `PAYMENTS_DB_USERNAME`, `PAYMENTS_DB_PASSWORD`

If using `tenancy.mode=DB_PER_EMPLOYER`, each service requires a map of per-employer DB configs (see `docs/tenancy-db-per-employer.md`).

## Messaging
If running Kafka/Rabbit in production, configure TLS and credentials via your platform’s mechanisms.
This repo assumes:
- Kafka topics are provisioned and ACL’d externally.
- Rabbit credentials are provisioned externally.
