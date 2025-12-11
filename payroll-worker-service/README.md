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