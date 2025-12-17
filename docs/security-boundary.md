# Security boundary plan (first pass)

## Goals
- Enforce authentication/authorization at a single ingress point (edge/gateway).
- Keep internal service-to-service calls protected, without coupling the domain core.
- Allow a simple local-development mode while establishing a clear production target.

## Boundary model
- External clients talk only to the Edge/Gateway.
- The gateway forwards requests to internal services (primarily `payroll-worker-service`).
- Internal services are not directly internet-exposed in production.

## Phase 1 (implementable now)
### Gateway auth (JWT)
- Gateway validates JWT on inbound requests.
- Gateway enforces route-level authorization (scopes/roles → endpoint groups).
- Gateway enforces employer scoping: tokens must be authorized for `/employers/{employerId}/...`.
- Gateway propagates:
  - `X-Correlation-ID` (generated if missing)
  - identity context as headers to downstream services:
    - `X-Principal-Sub`
    - `X-Principal-Scope`
    - `X-Employer-Id`

Local dev options:
- Allow auth disabled (explicitly) for faster iteration.
- Or validate HS256 JWTs using a shared dev HMAC secret.

Production target:
- OIDC/JWT validation using an IdP (issuer URI + rotating JWK set).

### Internal auth (internal JWT or shared secret)
For internal-only endpoints (e.g., orchestrator "internal execution" routes), require one of:
- **Preferred**: short-lived internal JWTs (HS256) using `Authorization: Bearer <token>`.
- **Fallback**: a shared-secret header (e.g. `X-Internal-Token`).

Operational expectations:
- Store secrets in a secret manager and inject via environment.
- Keep JWT TTL short and rotate signing keys.
- Header-based shared secrets are a compatibility/fallback mechanism.

## Phase 2 (production hardening)
### Service-to-service auth (mTLS or signed JWT)
- Prefer mTLS between pods/services (SPIFFE/SPIRE, service mesh, or platform-native mTLS).
- Alternatively, issue short-lived internal JWTs signed by the gateway or an internal issuer.

### Authorization model
- Decide the authoritative tenant isolation strategy:
  - schema-per-tenant, or
  - row-level security (RLS), or
  - per-tenant databases.
- Enforce the chosen strategy at persistence boundaries.

### Auditability
- Emit security audit events for:
  - login/auth failures
  - access denied decisions
  - admin/config changes

## Local dev: compose + gateway auth
### Run with gateway-only exposure
By default, `docker-compose.yml` publishes only:
- `edge-service` on `localhost:8080`, and
- Postgres on `localhost:5432`.

### Expose internal service ports (debug mode)
If you want to hit internal services directly for debugging, use the included `docker-compose.override.yml` (Compose loads it automatically if present):
- `hr-service`: `localhost:8081`
- `tax-service`: `localhost:8082`
- `labor-service`: `localhost:8083`
- `payroll-orchestrator-service`: `localhost:8085`
- `payroll-worker-service`: `localhost:8088`

### Mint a dev HS256 JWT (for EDGE_AUTH_MODE=HS256)
1. Start compose with HS256 enabled:
   - Set `EDGE_AUTH_MODE=HS256`
   - Set `EDGE_AUTH_HS256_SECRET` to your dev secret
2. Mint a token:
   - `EDGE_AUTH_HS256_SECRET=... ./scripts/mint-dev-jwt.sh`
3. Call through the gateway (demo endpoint routed by edge to worker):
   - `curl -H "Authorization: Bearer <token>" http://localhost:8080/dry-run-paychecks`

## Implementation notes in this repo
- `payroll-orchestrator-service` already guards `/payruns/internal/**` with a shared-secret filter.
- Ensure the shared secret defaults to blank and is configured explicitly per environment.
