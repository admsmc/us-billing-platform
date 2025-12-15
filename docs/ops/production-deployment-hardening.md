# Production deployment hardening (Docker + Kubernetes)

This document describes the production-hardened deployment baseline for this repository.

It covers:
- container runtime hardening (non-root, pinned base images)
- Kubernetes production patterns (probes, resources, HPA/PDB, network policies)
- operational requirements (secrets, dependencies, scaling)

## Container hardening (Docker)

### Base image pinning
Service Dockerfiles pin their base images by digest (both build stage and runtime stage). This makes builds more reproducible and reduces risk from upstream tag mutation.

### Non-root runtime
Service images run as a dedicated non-root user:
- `uid=10001`, `gid=10001`

### Read-only root filesystem
The Kubernetes manifests assume `readOnlyRootFilesystem: true`.

Applications should only need a writable `/tmp` directory. The manifests mount an `emptyDir` volume at `/tmp` and the Dockerfiles set `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/tmp`.

## Kubernetes baseline (Kustomize)

Kubernetes manifests live under `deploy/k8s/` and are organized as:
- `deploy/k8s/base`: core resources with secure-by-default pod security settings
- `deploy/k8s/overlays/dev`: dev-friendly overlay (dev-only Postgres, example Secrets, edge exposed via LoadBalancer)
- `deploy/k8s/overlays/prod`: production overlay (Ingress, PDB/HPA, default-deny network policies)

### Apply (dev)
```sh
kubectl apply -k deploy/k8s/overlays/dev
```

### Apply (prod)
```sh
kubectl apply -k deploy/k8s/overlays/prod
```

You must replace placeholder values in the prod overlay (e.g., Ingress hostnames, TLS secret name, image repository/tag strategy).

## Health probes

The services expose Spring Boot actuator probe endpoints:
- `/actuator/health/liveness`
- `/actuator/health/readiness`

The manifests use:
- `startupProbe` for DB-backed services (Flyway migrations can take time)
- `readinessProbe` on `/actuator/health/readiness`
- `livenessProbe` on `/actuator/health/liveness`

Design intent:
- readiness should reflect *local required dependencies* (primarily DB) but should not include upstream service-to-service dependencies (to avoid cascading failures)
- liveness should be a “process is healthy” signal; startupProbe prevents liveness killing the process during cold start/migrations

## Security posture (pods)

Base manifests apply these defaults:
- `runAsNonRoot: true`, `runAsUser/runAsGroup: 10001`
- `allowPrivilegeEscalation: false`
- `capabilities.drop: ["ALL"]`
- `seccompProfile: RuntimeDefault`
- `readOnlyRootFilesystem: true`
- `automountServiceAccountToken: false` (per-service ServiceAccount)

## Service exposure

- Internal services are `ClusterIP`.
- `edge-service` is the only intended entrypoint:
  - dev overlay: `Service.type=LoadBalancer`
  - prod overlay: `Ingress` skeleton (controller-specific annotations must be set)

## Scaling and disruption controls

Prod overlay adds:
- `PodDisruptionBudget` (minAvailable=1) per stateless service
- `HorizontalPodAutoscaler` for `edge-service` and `payroll-worker-service`
- `replicas: 2` baseline for stateless services

## Network policies

Prod overlay enables:
- default-deny ingress/egress
- explicit allows for:
  - ingress to edge
  - edge -> worker
  - worker -> HR/Tax/Labor
  - DNS egress
  - (placeholder) DB egress rules

Note: NetworkPolicies require a CNI that enforces them.

## Operational requirements (secrets and dependencies)

### Required secrets (baseline)
In production, supply these secrets via a secret manager and inject them into the cluster:

- `edge-auth`:
  - `issuer-uri`
  - `jwk-set-uri`
- `hr-db`:
  - `jdbc-url`
  - `username`
  - `password`
- `tax-db`:
  - `jdbc-url`
  - `username`
  - `password`
- `labor-db`:
  - `jdbc-url`
  - `username`
  - `password`
- `orchestrator-internal-auth`:
  - `shared-token`

### Postgres / database provisioning
The dev overlay includes a Postgres StatefulSet for convenience.

For production deployments, prefer a managed Postgres.
You must provision:
- databases/schemas per service
- users/roles and least-privilege grants
- connectivity (VPC/network + security groups) and TLS

## Image publishing conventions

The manifests use placeholder images of the form:
- `ghcr.io/REPLACE_ME/us-payroll-platform/<service>:REPLACE_TAG`

For production, prefer immutable tags (e.g. git SHA) and/or pin deployment image references by digest.
