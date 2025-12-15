# Kubernetes deployment baseline (Kustomize)

This directory provides a production-grade deployment baseline organized with Kustomize.

Layout:
- `deploy/k8s/base`: core services with secure-by-default runtime settings
- `deploy/k8s/overlays/dev`: dev-friendly overlay (includes dev-only Postgres + example Secrets + edge as LoadBalancer)
- `deploy/k8s/overlays/prod`: production overlay (Ingress skeleton + PDB/HPA + default-deny NetworkPolicies)

## Apply (dev)
```sh
kubectl apply -k deploy/k8s/overlays/dev
```

## Apply (prod)
```sh
kubectl apply -k deploy/k8s/overlays/prod
```

## Notes
- Image references are placeholders and must be set for your environment.
- In production, inject secrets via a secret manager (CSI driver / external-secrets / platform-native).
- Readiness/liveness probes use Spring Boot actuator probe endpoints (`/actuator/health/readiness`, `/actuator/health/liveness`).

See `docs/ops/production-deployment-hardening.md` for operational requirements (secrets, dependencies, scaling).
