# Threat model (baseline)
This document captures a first-pass threat model for the platform.

## Assets
- Payroll artifacts: payruns, paychecks, paycheck audits
- PII: employee identity data, compensation and deductions
- Money movement artifacts: payment initiation requests and payment status
- Credentials/secrets: DB credentials, internal auth secrets, OIDC configuration
- Operational controls: replay tools, tenant DB provisioning and restore

## Trust boundaries
- Internet/external clients → edge-service (ingress)
- edge-service → internal services (private network)
- Internal services → per-tenant databases (DB-per-employer)
- Internal services → message broker (RabbitMQ/Kafka)
- CI/CD → build artifacts and deployment pipeline

## Entry points
- edge-service HTTP endpoints
- internal operational endpoints (e.g. `/internal/**`)
- queue consumers (worker jobs)
- admin/operator tooling (scripts under `scripts/ops/`)

## High-risk threats and mitigations
### Unauthorized tenant access
Threat:
- A caller accesses another tenant’s data (horizontal privilege escalation).
Mitigations:
- Edge enforces employer scoping for `/employers/{employerId}/...`.
- DB-per-employer isolation prevents cross-tenant reads/writes by topology.
Open risks:
- Any future non-path-based tenant selectors must be treated as high risk.

### Privileged operations abuse
Threat:
- Replay, approve, payment initiation, or config changes performed without appropriate authorization.
Mitigations:
- Edge enforces scopes; `payroll:admin` is an umbrella.
- Security audit logs exist for authn/authz failures and break-glass.
- Privileged operation catalog: `docs/security-privileged-ops.md`.
Open risks:
- Some privileged actions may not yet emit explicit success audit events.

### Secret leakage via logs
Threat:
- Authorization headers or internal tokens end up in logs and are exfiltrated.
Mitigations:
- Request logging avoids headers/bodies.
- `web-core:LogRedactor` provides a guardrail for any future header/token logging.

### Injection / unsafe input
Threat:
- SQL injection, SSRF, path traversal, etc.
Mitigations:
- CodeQL SAST runs in CI.
- DB operations use parameter binding in hot paths.
Open risks:
- Any operator scripts that accept free-form user input must be treated carefully.

### Supply-chain compromise
Threat:
- Dependency compromise or malicious updates.
Mitigations:
- Gradle dependency locking + verification metadata.
- OWASP Dependency-Check CI and Trivy filesystem scanning.
- CycloneDX SBOM generation and validation.

### Runtime vulnerability exposure
Threat:
- Known vulnerabilities in deployed containers/services.
Mitigations:
- Trivy scan in CI.
- (Baseline) DAST workflow runs ZAP scan against edge-service.

## Testing and evidence
- CI: CodeQL (`.github/workflows/codeql.yml`)
- CI: security scan suite (`.github/workflows/security-scan.yml`)
- CI: DAST baseline (`.github/workflows/dast.yml`)

## Next iteration candidates
- Add a formal privileged-operation event store (append-only) in addition to logs.
- Move internal auth from symmetric HS256 to asymmetric keys/JWKS or mTLS.
- Add abuse-detection alerting on auth failures and break-glass usage.
