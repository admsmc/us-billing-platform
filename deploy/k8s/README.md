# Kubernetes skeleton (minimal)
This directory provides a minimal starting point for deploying the core services.

Intended usage:
- Start with a single namespace.
- Deploy Postgres (dev-only) or replace with a managed Postgres.
- Deploy `edge-service` as the only externally exposed service.

Apply:
- `kubectl apply -f deploy/k8s/namespace.yaml`
- `kubectl apply -f deploy/k8s/postgres.yaml`
- `kubectl apply -f deploy/k8s/hr-service.yaml`
- `kubectl apply -f deploy/k8s/tax-service.yaml`
- `kubectl apply -f deploy/k8s/labor-service.yaml`
- `kubectl apply -f deploy/k8s/payroll-worker-service.yaml`
- `kubectl apply -f deploy/k8s/edge-service.yaml`

Notes:
- These manifests are intentionally minimal and use environment variables for configuration.
- Secrets are placeholders; in production use a secret manager + CSI driver or platform-native secret injection.
