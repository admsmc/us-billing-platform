# Runtime Architecture

## Overview

The platform is deployed as a **multi-service topology** within a single monorepo. Each bounded context is exposed as a Spring Boot application with its own HTTP API, while sharing a common payroll domain core and value types.

At a high level:

- **payroll-domain** remains a pure calculation library and is not deployed on its own.
- **hr-service** is a Spring Boot service that owns employee and pay period data.
- **tax-service** is a Spring Boot service that owns tax rules and exposes tax contexts and tools.
- **labor-service** is a Spring Boot service that owns labor standards (minimum wage, overtime thresholds, tip credit rules).
- **payroll-worker-service** is a Spring Boot service that orchestrates pay runs by calling HR, Tax, and Labor over HTTP and invoking the payroll domain.

This topology trades some deployment complexity for clear service boundaries and the ability to scale/operate components independently.

## Services and responsibilities

### HR-Service

- **Module**: `hr-service`
- **Deployable**: Spring Boot app (e.g., `HrApplication`)
- **Primary responsibilities**:
  - Owns the `employee` and `employment_compensation` tables.
  - Owns the `pay_period` table.
  - Implements `EmployeeSnapshotProvider` and `PayPeriodProvider` ports.
  - Exposes HTTP endpoints under `/employers/{employerId}` for:
    - `GET /employees/{employeeId}/snapshot?asOf=YYYY-MM-DD`
    - `GET /pay-periods/{payPeriodId}`
- **Consumers**:
  - `payroll-worker-service` via `HrClient`.

### Tax-Service

- **Module**: `tax-service`
- **Deployable**: Spring Boot app (e.g., `TaxServiceApplication`)
- **Primary responsibilities**:
  - Owns the `tax_rule` table and import/validation tooling for tax configs.
  - Implements `TaxContextProvider` to assemble `TaxContext` from DB-backed rules.
  - Exposes HTTP endpoints under `/employers/{employerId}` for:
    - `GET /tax-context?asOf=YYYY-MM-DD` – returns a serialized `TaxContext` view for the given employer/date.
    - Future: endpoints to trigger tax-config validation or import runs.
- **Consumers**:
  - `payroll-worker-service` via `TaxClient`.

### Labor-Service

- **Module**: `labor-service`
- **Deployable**: Spring Boot app (e.g., `LaborServiceApplication`)
- **Primary responsibilities**:
  - Owns labor standards data (e.g., `labor_standards` table populated from CSV/JSON).
  - Implements a `LaborStandardsContextProvider` that returns per-state, per-employer labor standards.
  - Exposes HTTP endpoints under `/employers/{employerId}` for:
    - `GET /labor-standards?asOf=YYYY-MM-DD&state=CA` – returns the effective labor standards context.
- **Consumers**:
  - `payroll-worker-service` via `LaborStandardsClient`.

### Worker-Service

- **Module**: `payroll-worker-service`
- **Deployable**: Spring Boot app (e.g., `WorkerApplication`)
- **Primary responsibilities**:
  - Accept external requests to calculate paychecks or pay runs.
  - Compose data from HR, Tax, and Labor services.
  - Invoke `PayrollEngine.calculatePaycheck` from `payroll-domain`.
  - Surface detailed `CalculationTrace` (or a summarized view) to callers.
- **Key HTTP endpoints** (representative):
  - `POST /employers/{employerId}/payruns` – run payroll for a set of employees and periods.
  - `POST /employers/{employerId}/paychecks/preview` – preview a single paycheck.
- **Dependencies**:
  - `HrClient` – HTTP client for HR service.
  - `TaxClient` – HTTP client for tax service.
  - `LaborStandardsClient` – HTTP client for labor service.

## Communication patterns

### Synchronous HTTP between services

- Worker-service uses synchronous HTTP calls to HR, Tax, and Labor services for each pay run step.
- Clients are thin wrappers over Spring WebClient or RestTemplate, with:
  - Service base URL configured via external configuration (e.g., `hr.base-url`, `tax.base-url`, `labor.base-url`).
  - Correlation IDs propagated via an `X-Correlation-ID` header.
- Error handling:
  - Non-2xx responses are wrapped into typed exceptions (e.g., `HrServiceException`, `TaxServiceException`, `LaborServiceException`).
  - Worker-service translates these into structured error responses for its own API.

### Local (in-process) mode for tests

For integration tests and local experimentation, the same functional boundaries can be exercised in a single JVM without HTTP:

- Tests can instantiate in-memory implementations of `HrClient`, `TaxClient`, and `LaborStandardsClient` that call the underlying ports directly.
- This keeps tests fast while still reflecting the external topology.

## Correlation IDs and logging

### Correlation ID strategy

- Use a simple string correlation ID propagated via HTTP header `X-Correlation-ID`.
- Worker-service:
  - On external entry, generates a correlation ID if one is not provided.
  - Stores it in a logging MDC (Mapped Diagnostic Context) under `correlationId`.
  - Passes it to downstream HR/Tax/Labor service calls via `X-Correlation-ID` header.
- HR/Tax/Labor services:
  - Extract `X-Correlation-ID` from incoming requests (or generate one if missing).
  - Store it in MDC for all logs in the request scope.

### Structured logging format

- Services log in a **structured key-value** format (e.g., JSON) including at minimum:
  - `timestamp`
  - `level`
  - `service` (e.g., `hr-service`, `tax-service`, `labor-service`, `worker-service`)
  - `correlationId`
  - `logger`
  - `message`
  - Optional fields: `employerId`, `employeeId`, `payRunId`, `paycheckId`.
- Logging libraries:
  - Use SLF4J with a JSON-capable backend (e.g., logstash-logback encoder) in production profiles.
  - For local development, a human-readable pattern layout can be used.

## Error handling and resilience

- Replace generic `error("...")` and unchecked exceptions at HTTP boundaries with:
  - Typed exceptions in each service layer.
  - Global `@ControllerAdvice` handlers that map exceptions to structured error responses with:
    - `errorCode` (stable, machine-readable).
    - `message` (human-readable, non-sensitive).
    - `correlationId`.
- Worker-service behavior on dependency failures:
  - For HR/Tax/Labor timeouts or 5xx errors, respond with `503 Service Unavailable` and a structured body.
  - For 4xx errors (e.g., unknown employee or pay period), respond with `404 Not Found` or `400 Bad Request` as appropriate.

## Deployment modes

### Local development

- All four services can be run locally on different ports (e.g., HR 8081, Tax 8082, Labor 8083, Worker 8080).
- Alternatively, tests and experiments can run in a single JVM using in-memory clients and H2 databases.

### Staging/production

- Each service is deployed as an independent container/pod with its own:
  - Configuration (DB connection strings, base URLs, logging/metrics config).
  - Horizontal scaling policy (e.g., more replicas for worker-service).
- Shared concerns (auth, rate limiting, TLS termination) are handled by an API gateway or ingress in front of worker-service and any directly exposed service APIs.

## Rationale for multi-service over modular monolith

- Clear operational boundaries:
  - HR, Tax, and Labor change at different cadences and can be owned by different teams.
  - Tax and Labor imports/validations are operational workflows that benefit from independent scaling and maintenance.
- Strong isolation of responsibilities:
  - Tax and Labor services can be reused by non-payroll consumers (e.g., benefits systems) without going through worker-service.
- Future readiness:
  - Easier to introduce caching tiers, region-specific deployments, and blue/green rollouts per service.

The codebase still respects modular boundaries (per `docs/architecture.md`), so if needed, the same modules can also be run in a more monolithic mode for simpler deployments. The multi-service topology described here is the target runtime architecture for production environments.