# payroll-worker-service

## Garnishment observability (metrics)

`payroll-worker-service` exposes a couple of Micrometer metrics related to garnishment activity via the standard Spring Boot Actuator `/actuator/prometheus` endpoint (assuming Prometheus export is enabled in your deployment).

### Metrics

1. **Employees with active garnishments per run**

- **Name:** `payroll_garnishments_employees_with_orders_total`
- **Type:** Counter
- **Tags:**
  - `employer_id` – the employer external ID

This counter is incremented once per `(employer_id, employee)` whenever HR returns at least one active garnishment order for that employee during an HR‑backed payroll run.

2. **Protected earnings floor applied**

- **Name:** `payroll_garnishments_protected_floor_applied_total`
- **Type:** Counter
- **Tags:**
  - `employer_id` – the employer external ID

This counter is incremented by the number of `ProtectedEarningsApplied` events in a paycheck trace. In other words, it counts how many garnishment orders had their requested amount reduced by a protected‑earnings floor.

### Prometheus examples

Assuming you scrape `/actuator/prometheus` from worker‑service, you can use queries like:

- Total employees with active garnishments per employer:

  ```promql
  sum by (employer_id) (payroll_garnishments_employees_with_orders_total)
  ```

- Rate of employees with active garnishments over the last 5 minutes:

  ```promql
  sum by (employer_id) (
    rate(payroll_garnishments_employees_with_orders_total[5m])
  )
  ```

- Total number of times a protected floor constrained garnishments per employer:

  ```promql
  sum by (employer_id) (payroll_garnishments_protected_floor_applied_total)
  ```

- Rate of protected‑floor applications (e.g., for alerting on spikes):

  ```promql
  sum by (employer_id) (
    rate(payroll_garnishments_protected_floor_applied_total[5m])
  )
  ```

These metrics are emitted by `PayrollRunService.runHrBackedPayForPeriod`, so they only reflect HR‑backed flows (i.e., runs that use `HrClient` for employee data and garnishment orders).

3. **HR garnishment-related HTTP errors**

- **Name:** `payroll_garnishments_hr_errors_total`
- **Type:** Counter
- **Tags:**
  - `endpoint` – a label describing which HR operation failed (e.g., `GET garnishments`, `POST garnishment withholdings`).

This counter is incremented when the worker-service exhausts its retry budget calling HR for garnishment endpoints and ultimately gives up (either by throwing for critical operations or by logging and degrading gracefully for non-critical ones). It can be used to alert on persistent HR connectivity or availability issues specific to garnishment flows.

## HR client configuration (timeouts and retries)

The HTTP-based `HrClient` used by worker-service is configured via the `downstreams.hr.*` property namespace:

- `downstreams.hr.base-url` – base URL for the HR service (default: `http://localhost:8081`).
- `downstreams.hr.connect-timeout` – connection timeout as a Java `Duration` (default: `PT2S`).
- `downstreams.hr.read-timeout` – read timeout as a Java `Duration` (default: `PT5S`).
- `downstreams.hr.max-retries` – number of retry attempts for transient failures on non-critical endpoints (default: `2`).

Example application configuration snippet (YAML):

```yaml
downstreams:
  hr:
    base-url: "http://hr-service:8081"
    connect-timeout: "PT1S"   # 1 second connect timeout
    read-timeout: "PT3S"      # 3 second read timeout
    max-retries: 3             # up to 4 total attempts (1 initial + 3 retries)
```

Timeouts are applied via the underlying `SimpleClientHttpRequestFactory`, and bounded retry/backoff is applied via `web-core`’s `HttpClientGuardrails`. Garnishment-related endpoints (`/garnishments` and `/garnishments/withholdings`) use `failOnExhaustion = false`, meaning the worker-service logs and falls back rather than failing the entire payroll run when HR is unavailable.

### Manual replay of garnishment withholdings

For operational recovery, a small CLI helper is provided:

- **Class:** `com.example.uspayroll.worker.tools.GarnishmentWithholdingReplayCli`
- **Usage:**

  ```bash
  java -cp payroll-worker-service/build/libs/payroll-worker-service-all.jar \
    com.example.uspayroll.worker.tools.GarnishmentWithholdingReplayCli \
    http://localhost:8081 EMP-HR-HTTP EE-HTTP-1 /path/to/withholding-payload.json
  ```

The JSON file should contain a payload compatible with the HR `/garnishments/withholdings` endpoint (i.e., the same shape worker-service sends during normal runs). This tool is intentionally simple and intended for manual use when re-sending a previously logged payload is necessary.
