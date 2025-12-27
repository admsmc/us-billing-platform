# Secrets integration for Kubernetes deployments

This repo assumes that production clusters source all sensitive configuration from a secret manager rather than committing real secrets to Git.

This document sketches a concrete pattern using a secret-manager-backed controller (for example, External Secrets Operator) while keeping application manifests in `deploy/k8s/**` clean and portable.

## Goals

- No production or shared environment credentials committed to Git.
- Per-service, least-privilege credentials managed centrally (cloud secret manager or Vault).
- Kubernetes manifests in this repo reference only Kubernetes `Secret` names/keys, never raw secret values.

## High-level pattern

1. **Authoritative secrets live in your secret manager** (cloud-specific or Vault), for example:
   - `us-payroll/hr/db` → contains `jdbc-url`, `username`, `password`
   - `us-payroll/tax/db` → contains `jdbc-url`, `username`, `password`
   - `us-payroll/labor/db` → contains `jdbc-url`, `username`, `password`
   - `us-payroll/orchestrator/db` → contains `jdbc-url`, `username`, `password`
   - `us-payroll/edge/auth` → contains `issuer-uri`, `jwk-set-uri`
   - `us-payroll/orchestrator/internal-auth` → contains `jwt-secret` (or a keyring map)

2. **A controller in the cluster** (for example, External Secrets Operator or a cloud-native CSI driver) syncs those secrets into Kubernetes `Secret` resources in the `us-payroll` namespace.

3. **Application manifests in this repo** (`deploy/k8s/base/*.yaml`, overlays) reference only those Kubernetes `Secret` names and keys.

The `deploy/k8s/overlays/dev/secrets-dev.yaml` file remains a **dev-only example** for local clusters. It uses clearly marked, low-value placeholders and should not be reused in shared or production environments.

## Example: External Secrets Operator

The following example shows how you might wire the `hr-db` Secret from a cloud secret manager into Kubernetes using External Secrets Operator. Paths and APIs will depend on your secret manager.

```yaml path=null start=null
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: hr-db
  namespace: us-payroll
spec:
  refreshInterval: 1h
  secretStoreRef:
    name: us-payroll-secret-store
    kind: ClusterSecretStore
  target:
    name: hr-db
    creationPolicy: Owner
  data:
    - secretKey: jdbc-url
      remoteRef:
        key: us-payroll/hr/db
        property: jdbc-url
    - secretKey: username
      remoteRef:
        key: us-payroll/hr/db
        property: username
    - secretKey: password
      remoteRef:
        key: us-payroll/hr/db
        property: password
```

You would define similar `ExternalSecret` resources for:

- `tax-db`
- `labor-db`
- `orch-db`
- `edge-auth`
- `orchestrator-internal-auth`

The base manifests in `deploy/k8s/base/*.yaml` already assume that these `Secret` objects exist and only reference their keys via `valueFrom.secretKeyRef`.

## Recommended production practice

- Manage the lifecycle of these secrets (creation, rotation, revocation) in your secret manager, not in Kubernetes YAML.
- Use per-environment secret stores (e.g., `us-payroll-prod`, `us-payroll-staging`) and map them to namespaces via `ClusterSecretStore`.
- For rotation of internal JWT secrets, follow the keyring pattern described in `docs/ops/secrets-and-configuration.md` and source each key from the secret manager.

## Local and CI environments

- For **local clusters**, it is acceptable to apply `deploy/k8s/overlays/dev` as-is, which includes `secrets-dev.yaml` with clearly-marked, dev-only credentials.
- For **shared dev or CI clusters**, prefer swapping `secrets-dev.yaml` for ExternalSecret definitions that pull from a non-production secret store.
