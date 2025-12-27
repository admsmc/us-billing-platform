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

Additional enterprise-grade permissions supported by edge policy:
- `payroll:bench` (benchmark-only endpoints under `/benchmarks/**`)
- `payroll:replay` (internal operational endpoints under `/internal/**`)
- `payroll:ops` (legacy job endpoints under `/jobs/**`)
- Employer scoping:
  - `employer_id` (single employer) and/or
  - `employer_ids` (array of employers)
  - Optional break-glass:
    - `platform_admin=true`

## Internal service-to-service auth
Internal operational endpoints are protected and should never be exposed publicly.

mTLS remains an infrastructure concern (service mesh / SPIFFE), but this repo supports an application-level internal JWT (HS256) option so service-to-service auth can be production-grade even without a mesh.

### Orchestrator internal endpoints
Orchestrator internal endpoints (e.g. `/payruns/internal/**`) require a short-lived internal JWT (HS256).

#### Configuration via Spring Boot Profiles (Recommended)
For development and benchmarks, use Spring profiles (e.g. `application-benchmark.yml`):
```yaml
orchestrator:
  internal-auth:
    jwt-keys:
      k1: <secret>
    jwt-default-kid: k1
```

Activate via: `SPRING_PROFILES_ACTIVE=benchmark`

#### Configuration via Environment Variables
For production Kubernetes/Docker deployments, use environment variables:
- Verifier keyring:
  - Via property: `orchestrator.internal-auth.jwt-keys.<kid>=<random>`
  - Note: Environment variable Map binding requires YAML-in-JSON approach:
    ```yaml
    SPRING_APPLICATION_JSON: |
      {"orchestrator":{"internal-auth":{"jwt-keys":{"k1":"<secret>"}}}}
    ```
  - Or use `orchestrator.internal-auth.jwt-shared-secret=<random>` for single-key setup
- Token claims constraints:
  - `orchestrator.internal-auth.jwt-issuer=us-payroll-platform` (default)
  - `orchestrator.internal-auth.jwt-audience=payroll-orchestrator-service` (default)
- Request header:
  - `Authorization: Bearer <internal-jwt>`

### Worker → orchestrator internal client auth
The worker issues internal JWTs when calling orchestrator internal endpoints:
- `downstreams.orchestrator.internal-jwt-secret=<random>`
- `downstreams.orchestrator.internal-jwt-kid=k1` (must match orchestrator’s configured keyring kid)
- `downstreams.orchestrator.internal-jwt-issuer=us-payroll-platform` (default)
- `downstreams.orchestrator.internal-jwt-audience=payroll-orchestrator-service` (default)
- `downstreams.orchestrator.internal-jwt-subject=payroll-worker-service` (default)
- `downstreams.orchestrator.internal-jwt-ttl-seconds=60` (default)

### Internal JWT key rotation runbook (HS256)
This runbook assumes you are using the orchestrator verifier keyring (`orchestrator.internal-auth.jwt-keys.*`) and the worker internal JWT client.

1) Generate a new random secret and choose a new `kid` (e.g. rotate from `v1` → `v2`).
2) Deploy the orchestrator with **both** keys present:
   - `orchestrator.internal-auth.jwt-keys.v1=<old>`
   - `orchestrator.internal-auth.jwt-keys.v2=<new>`
   - (Optional) keep `orchestrator.internal-auth.jwt-default-kid=v1`.
3) Deploy the worker configured to issue tokens with the new `kid`:
   - `downstreams.orchestrator.internal-jwt-kid=v2`
   - `downstreams.orchestrator.internal-jwt-secret=<new>`
4) Observe for a full rollout window (longer than the token TTL). The default TTL is 60 seconds.
5) Retire the old key by removing it from the orchestrator keyring (remove `jwt-keys.v1`).

### Worker internal endpoints
Worker internal endpoints (e.g. DLQ replay) require a short-lived internal JWT (HS256):
- `worker.internal-auth.jwt-keys.<kid>=<random>` (e.g. `worker.internal-auth.jwt-keys.k1=...`)
- `worker.internal-auth.jwt-default-kid=k1` (optional)
- `worker.internal-auth.jwt-issuer=us-payroll-platform` (default)
- `worker.internal-auth.jwt-audience=payroll-worker-service` (default)
- Request header:
  - `Authorization: Bearer <internal-jwt>`

## Databases (per service)
In production, supply DB credentials via secrets sourced from your secret manager (not static YAML).
Typical required values:
- HR: `HR_DB_URL`, `HR_DB_USERNAME`, `HR_DB_PASSWORD`
- Tax: `TAX_DB_URL`, `TAX_DB_USERNAME`, `TAX_DB_PASSWORD`
- Labor: `LABOR_DB_URL`, `LABOR_DB_USERNAME`, `LABOR_DB_PASSWORD`
- Orchestrator: `ORCH_DB_URL`, `ORCH_DB_USERNAME`, `ORCH_DB_PASSWORD`
- Payments: `PAYMENTS_DB_URL`, `PAYMENTS_DB_USERNAME`, `PAYMENTS_DB_PASSWORD`

If using `tenancy.mode=DB_PER_EMPLOYER`, each service requires a map of per-employer DB configs (see `docs/tenancy-db-per-employer.md`).

In Kubernetes, production deployments should source these secrets from a secret manager (for example, via External Secrets Operator or a cloud-specific CSI driver) rather than committing any real credentials to Git. Dev overlays in `deploy/k8s/overlays/dev` use clearly-marked, non-production placeholders.

## Messaging
If running Kafka/Rabbit in production, configure TLS and credentials via your platform’s mechanisms.
This repo assumes:
- Kafka topics are provisioned and ACL’d externally.
- Rabbit credentials are provisioned externally.
