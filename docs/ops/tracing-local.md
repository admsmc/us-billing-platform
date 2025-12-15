# Local tracing (OpenTelemetry)
This repo uses Micrometer Tracing with the OpenTelemetry bridge. Services export traces over OTLP.

## Choose a backend
Recommended:
- Local dev: Jaeger
- Long-term: Grafana Tempo

## Run Jaeger (local dev)
From the repo root:
- `docker compose -f docker-compose.observability-jaeger.yml up`

Jaeger UI:
- http://localhost:16686

Set this env var for each service:
- `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318/v1/traces`

## Run Tempo (long-term style)
From the repo root:
- `docker compose -f docker-compose.observability-tempo.yml up`

Tempo HTTP endpoint:
- http://localhost:4318/v1/traces

Query API:
- http://localhost:3200

Set this env var for each service:
- `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318/v1/traces`

## Sampling
Sampling is configured per service via:
- `TRACING_SAMPLING_PROBABILITY` (default 0.1)

For local debugging you’ll usually want 100% sampling:
- `TRACING_SAMPLING_PROBABILITY=1.0`

## What to look for
For a payrun finalize flow, you should see spans across:
- edge-service (ingress)
- payroll-worker-service
- payroll-orchestrator-service
