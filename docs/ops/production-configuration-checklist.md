# Production configuration checklist

This checklist summarizes the minimum configuration required for a secure, production-ready deployment.

Use this as the reference when configuring Kubernetes overlays, CI/CD pipelines, and secret manager entries.

## Global

- All production secrets are sourced from a secret manager and synced into Kubernetes `Secret` objects.
- No production credentials are committed to Git (only dev-only examples under `deploy/k8s/overlays/dev`).
- Tracing and metrics endpoints are configured for your observability stack.

## edge-service

- `EDGE_AUTH_MODE=OIDC`.
- `EDGE_AUTH_ALLOW_DISABLED=false` (default; must not be overridden to `true`).
- `EDGE_AUTH_ISSUER_URI` or `EDGE_AUTH_JWK_SET_URI` is set from a Kubernetes `Secret` (`edge-auth`).
- Only edge-service is exposed externally (Ingress or LoadBalancer); all other services are `ClusterIP`.

## payroll-worker-service

- `DOWNSTREAMS_HR_BASE_URL`, `DOWNSTREAMS_TAX_BASE_URL`, `DOWNSTREAMS_LABOR_BASE_URL`, and `DOWNSTREAMS_ORCHESTRATOR_BASE_URL` point at in-cluster services.
- `DOWNSTREAMS_TIME_ENABLED` reflects whether time-ingestion-service is deployed and production-ready.
- Internal JWT client is configured if orchestrator internal endpoints are enabled:
  - `DOWNSTREAMS_ORCHESTRATOR_INTERNAL_JWT_SECRET` (from secret manager)
  - `DOWNSTREAMS_ORCHESTRATOR_INTERNAL_JWT_KID` (must match orchestrator keyring kid)

## payroll-orchestrator-service

- Database credentials come from `orch-db` Secret (backed by secret manager):
  - `SPRING_DATASOURCE_URL`
  - `SPRING_DATASOURCE_USERNAME`
  - `SPRING_DATASOURCE_PASSWORD`
- Internal auth keyring is configured via `orchestrator.internal-auth.jwt-keys.*` (or `jwtSharedSecret` in smaller environments) sourced from `orchestrator-internal-auth` Secret.

## hr-service, tax-service, labor-service, time-ingestion-service

- Each service has its own DB credentials from a per-service Secret:
  - `hr-db`, `tax-db`, `labor-db`, `time-db` (names may vary by environment).
- If `tenancy.mode=DB_PER_EMPLOYER` is used:
  - Each service’s `tenancy.databases` map is fully populated with valid JDBC URLs and credentials.
  - Flyway migration strategies are verified against all tenant databases.

## payments-service, reporting-service, filings-service

- Each service has its own DB credentials from a per-service Secret.
- Kafka configuration is set only when required and points to production clusters with TLS and auth configured outside the app.

## Observability

- `TRACING_SAMPLING_PROBABILITY` tuned for production (often lower than `0.1`).
- `OTEL_EXPORTER_OTLP_ENDPOINT` points at your production OTLP collector.
- Prometheus scraping is configured for `/actuator/prometheus` endpoints.

## Operational safeguards

- Kubernetes prod overlay includes:
  - PodDisruptionBudget for each stateless service.
  - HorizontalPodAutoscaler for edge-service and payroll-worker-service.
  - Default-deny NetworkPolicies with explicit allows between services.
- CI enforces basic config policy (see `.github/workflows/ci.yml`).
