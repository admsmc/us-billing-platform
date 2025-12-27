# Configuration contracts (service env and properties)

This document summarizes the canonical configuration contract for each service: the environment variables and configuration properties that must be supplied by the runtime environment.

It is intended as the single source of truth to keep `application.yml`, Docker Compose, and Kubernetes manifests aligned.

## Common conventions

- All services use `SERVER_PORT` to control the HTTP port.
- Tracing and metrics are controlled via:
  - `TRACING_SAMPLING_PROBABILITY` (default `0.1`)
  - `OTEL_EXPORTER_OTLP_ENDPOINT` (default `http://localhost:4318/v1/traces`)
- Database credentials are injected via env vars and K8s `Secret` references; in production they must be sourced from a secret manager.

---

## edge-service

**Env vars**

- `SERVER_PORT` (default `8080`)
- `WORKER_BASE_URL` (required): base URL for `payroll-worker-service`.
- `EDGE_AUTH_MODE` (default `HS256`)
  - Valid values: `DISABLED`, `HS256`, `OIDC`.
- `EDGE_AUTH_ALLOW_DISABLED` (default `false`)
  - Must remain `false` in production.
- `EDGE_AUTH_HS256_SECRET`
  - Required when `EDGE_AUTH_MODE=HS256`.
- `EDGE_AUTH_ISSUER_URI` / `EDGE_AUTH_JWK_SET_URI`
  - One of these must be set when `EDGE_AUTH_MODE=OIDC`.

---

## payroll-worker-service

**Env vars**

- `SERVER_PORT` (default `8088`)
- Downstream base URLs:
  - `DOWNSTREAMS_HR_BASE_URL`
  - `DOWNSTREAMS_TAX_BASE_URL`
  - `DOWNSTREAMS_LABOR_BASE_URL`
  - `DOWNSTREAMS_TIME_BASE_URL` (optional)
  - `DOWNSTREAMS_TIME_ENABLED` (default `false`)
  - `DOWNSTREAMS_ORCHESTRATOR_BASE_URL`
- Orchestrator internal JWT client:
  - `DOWNSTREAMS_ORCHESTRATOR_INTERNAL_JWT_SECRET` (required when internal endpoints enabled)
  - `DOWNSTREAMS_ORCHESTRATOR_INTERNAL_JWT_KID` (default `k1`)

**Configuration properties**

- `worker.payroll.traceLevel`
  - Enum, defaults to `AUDIT`. Controls domain trace verbosity.
- `worker.internal-auth.*`
  - Internal JWT verification for worker internal endpoints.

---

## payroll-orchestrator-service

**Env vars**

- `SERVER_PORT` (default `8085`)
- DB:
  - `SPRING_DATASOURCE_URL`
  - `SPRING_DATASOURCE_USERNAME`
  - `SPRING_DATASOURCE_PASSWORD`
- Downstream base URLs:
  - `DOWNSTREAMS_HR_BASE_URL`
  - `DOWNSTREAMS_TAX_BASE_URL`
  - `DOWNSTREAMS_LABOR_BASE_URL`
  - `DOWNSTREAMS_TIME_BASE_URL` (optional)
  - `DOWNSTREAMS_TIME_ENABLED` (default `false`)

**Configuration properties**

- `orchestrator.internal-auth.*`
  - HS256 keyring for internal JWT verification (keyed by `kid`).
- `orchestrator.payments.*`
  - Enables consumption of payment events and topics.

---

## hr-service

**Env vars**

- `SERVER_PORT` (default `8081`)
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- Optional: tenancy mode when using DB-per-employer
  - `TENANCY_MODE` → bound as `tenancy.mode`

**Configuration properties**

- `tenancy.mode` (`SINGLE` or `DB_PER_EMPLOYER`)
- `tenancy.databases[<employerId>].url`

---

## tax-service

**Env vars**

- `SERVER_PORT` (default `8082`)
- `TAX_DB_URL`
- `TAX_DB_USERNAME`
- `TAX_DB_PASSWORD`

**Configuration properties**

- `tax.db.url`, `tax.db.username`, `tax.db.password`
- `tenancy.mode` / `tenancy.databases` when DB-per-employer is used.

---

## labor-service

**Env vars**

- `SERVER_PORT` (default `8083`)
- `LABOR_DB_URL`
- `LABOR_DB_USERNAME`
- `LABOR_DB_PASSWORD`

**Configuration properties**

- `labor.db.url`, `labor.db.username`, `labor.db.password`
- `tenancy.mode` / `tenancy.databases` when DB-per-employer is used.

---

## time-ingestion-service

**Env vars**

- `SERVER_PORT` (default `8084`)
- `TIME_DB_URL`
- `TIME_DB_USERNAME`
- `TIME_DB_PASSWORD`

**Configuration properties**

- `time.db.url`, `time.db.username`, `time.db.password`
- `tenancy.mode` / `tenancy.databases` when DB-per-employer is used.

---

## payments-service

**Env vars**

- `SERVER_PORT` (default `8087`)
- `PAYMENTS_DB_URL`
- `PAYMENTS_DB_USERNAME`
- `PAYMENTS_DB_PASSWORD`

**Configuration properties**

- `payments.events.*` (Kafka topics)
- `tenancy.mode` / `tenancy.databases` when DB-per-employer is used.

---

## reporting-service

**Env vars**

- `SERVER_PORT` (default `8089`)
- `REPORTING_DB_URL`
- `REPORTING_DB_USERNAME`
- `REPORTING_DB_PASSWORD`

---

## filings-service

**Env vars**

- `SERVER_PORT` (default `8090`)
- `FILINGS_DB_URL`
- `FILINGS_DB_USERNAME`
- `FILINGS_DB_PASSWORD`

**Configuration properties**

- `filings.kafka.*` (Kafka topics and consumer settings).

---

This document is intentionally concise. When updating configuration (env vars, properties, or manifests), update this file first and then align `application.yml`, Docker Compose, and `deploy/k8s/**` with the documented contract.