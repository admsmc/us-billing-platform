#!/usr/bin/env bash
# Phase 3 benchmark: Test with Redis caching + PgBouncer + performance indexes
#
# This script runs benchmarks with varying worker counts to validate Phase 3 improvements:
# - Redis caching for tax and labor standards (30-50% reduction in per-employee time)
# - PgBouncer connection pooling (enables 8-16 workers efficiently)
# - Performance indexes (reduces DB query overhead)
#
# Expected results:
# - 8 workers: 2-4x throughput improvement vs. 4 workers
# - 16 workers: Additional throughput gains (up to 8x vs. baseline)
# - Cache hit rates: >99% after first employee in payrun
#
# Usage:
#   ./benchmarks/run-phase3-bench.sh
#
# Advanced usage:
#   EMPLOYEE_COUNT=10000 WORKER_REPLICAS_CSV="4,8,12,16" ./benchmarks/run-phase3-bench.sh

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

# Defaults
EMPLOYEE_COUNT=${EMPLOYEE_COUNT:-10000}
WORKER_REPLICAS_CSV=${WORKER_REPLICAS_CSV:-"4,8,16"}
TRIALS=${TRIALS:-3}
OUT_DIR=${OUT_DIR:-"/tmp/us-payroll-phase3-bench"}

echo "==================================="
echo "Phase 3 Performance Benchmark"
echo "==================================="
echo "Employee count: $EMPLOYEE_COUNT"
echo "Worker replicas: $WORKER_REPLICAS_CSV"
echo "Trials per configuration: $TRIALS"
echo "Output directory: $OUT_DIR"
echo ""

# Ensure Redis and PgBouncer are enabled
export BENCH_DISABLE_PGBOUNCER=false
export BENCH_ENABLE_REDIS=true

# Run the main benchmark harness with Phase 3 compose files
exec "$ROOT_DIR/benchmarks/run-parallel-payrun-bench.sh" \
  EMPLOYEE_COUNT="$EMPLOYEE_COUNT" \
  WORKER_REPLICAS_CSV="$WORKER_REPLICAS_CSV" \
  TRIALS="$TRIALS" \
  OUT_DIR="$OUT_DIR"
