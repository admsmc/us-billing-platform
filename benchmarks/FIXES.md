# Benchmark Script Fixes

## Issues Resolved

### 1. Column Count Mismatch Prevention
**Problem:** Postgres statistics delta variables were not initialized before use, which could cause the script to crash or write incomplete CSV rows if the Python delta calculation failed or was skipped.

**Solution:** 
- All Postgres delta variables are now initialized to "nan" before delta calculation
- Variables are properly scoped and guaranteed to exist when writing the summary CSV
- This ensures header and data rows always have matching column counts (48 columns)

### 2. Terminal Buffer Issues
**Problem:** Very long lines (especially query snippets from pg_stat_statements) could cause terminal crashes when displayed.

**Solution:**
- Query snippets are now truncated to 80 characters maximum
- Added `BENCH_MINIMAL_OUTPUT` mode to completely disable Postgres statistics collection
- Existing `PG_DIAG_SAMPLE_EVERY` and `PG_STAT_SAMPLE_EVERY` controls documented and enhanced

### 3. Script Robustness
**Problem:** Script could fail silently if Postgres stats weren't available or sampling was disabled.

**Solution:**
- Postgres delta calculation is now wrapped in a conditional that respects `BENCH_MINIMAL_OUTPUT`
- All statistics collection is gracefully skipped when in minimal mode
- Clear logging indicates when minimal mode is active

## New Environment Variables

### BENCH_MINIMAL_OUTPUT
- **Type:** boolean (`true`/`false`)
- **Default:** `false`
- **Purpose:** Disable all Postgres statistics collection to minimize output verbosity
- **Usage:** `BENCH_MINIMAL_OUTPUT=true ./benchmarks/run-parallel-payrun-bench.sh`

When enabled:
- Postgres bgwriter, WAL, and pg_stat_statements metrics are not collected
- All Postgres delta columns in summary.csv will be "nan"
- Significantly reduces terminal output and processing overhead
- Basic metrics (CPU%, connections) are still collected

### PG_DIAG_SAMPLE_EVERY
- **Type:** integer
- **Default:** `3` (sample every ~15 seconds with 5s loop)
- **Purpose:** Control frequency of Postgres diagnostic queries (wait events, locks)
- **Usage:** `PG_DIAG_SAMPLE_EVERY=10 ./benchmarks/run-parallel-payrun-bench.sh`
- Set to `0` to disable diagnostics

### PG_STAT_SAMPLE_EVERY
- **Type:** integer
- **Default:** Same as `PG_DIAG_SAMPLE_EVERY`
- **Purpose:** Control frequency of Postgres statistics sampling (bgwriter, WAL, pg_stat_statements)
- **Usage:** `PG_STAT_SAMPLE_EVERY=10 ./benchmarks/run-parallel-payrun-bench.sh`
- Set to `0` to disable statistics sampling

## Usage Examples

### Minimal Output Mode (Recommended for Long Runs)
```bash
BENCH_MINIMAL_OUTPUT=true ./benchmarks/run-parallel-payrun-bench.sh
```

### Reduced Sampling (Still Collect Stats but Less Frequently)
```bash
PG_DIAG_SAMPLE_EVERY=10 PG_STAT_SAMPLE_EVERY=10 ./benchmarks/run-parallel-payrun-bench.sh
```

### Background Execution with Log File (Best Practice)
```bash
./benchmarks/run-parallel-payrun-bench.sh > /tmp/bench-$(date +%s).log 2>&1 &
tail -f /tmp/bench-*.log
```

### Minimal Mode + Background Execution
```bash
BENCH_MINIMAL_OUTPUT=true ./benchmarks/run-parallel-payrun-bench.sh > /tmp/bench-minimal-$(date +%s).log 2>&1 &
```

## Validation

A new validation script is included to verify CSV integrity:

```bash
./benchmarks/validate-summary-csv.sh /tmp/us-payroll-parallel-bench/summary.csv
```

This script checks that all data rows have the same column count as the header.

## Column Count Reference

The summary.csv now consistently has **48 columns**:

1. workers
2. employee_count
3. trials
4-17. k6 metrics (elapsed, throughput, polls)
18-19. DB resource maxes (cpu, connections)
20-21. Service timer averages (worker, orchestrator)
22-39. Postgres bgwriter and WAL deltas (18 columns)
40-45. pg_stat_statements metrics (6 columns)
46-48. Artifact paths (run_id, json, csv)

All Postgres delta columns (22-45) will be "nan" when `BENCH_MINIMAL_OUTPUT=true`.

## Migration Notes

No changes are required for existing benchmark invocations. The script is backward compatible:
- Default behavior unchanged (all stats collected)
- Existing environment variables work as before
- Summary CSV format is identical (now guaranteed consistent)

To take advantage of the new features, add the environment variables as shown above.
