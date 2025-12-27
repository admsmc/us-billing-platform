# Phase 3: Redis Caching and Performance Metrics

## Overview

Phase 3 implements Redis caching for tax and labor standards reference data, along with comprehensive performance metrics to track paycheck computation performance.

## Implementation

### Components

1. **Redis Infrastructure** (`docker-compose.redis.yml`)
   - Redis 7 Alpine with 256MB memory limit
   - LRU eviction policy
   - Port 6379 exposed for monitoring

2. **Cached Tax Client** (`CachedTaxClient.kt`)
   - Transparent decorator over `HttpTaxClient`
   - Cache key: `tax:{employerId}:{asOf}:{residentState}:{workState}:{localityCodes}`
   - TTL: 24 hours (configurable via `cache.tax-ttl-hours`)
   - Metrics: `uspayroll.cache.tax.hit` / `uspayroll.cache.tax.miss`

3. **Cached Labor Standards Client** (`CachedLaborStandardsClient.kt`)
   - Transparent decorator over `HttpLaborStandardsClient`
   - Cache key: `labor:{employerId}:{asOf}:{workState}:{homeState}:{localityCodes}`
   - TTL: 24 hours (configurable via `cache.labor-ttl-hours`)
   - Caches null results to avoid repeated 404s
   - Metrics: `uspayroll.cache.labor.hit` / `uspayroll.cache.labor.miss`

4. **Performance Metrics** (`PerformanceMetrics.kt`)
   - Per-employee processing time histogram with percentiles
   - Service call latencies (tax, labor, HR)
   - Cache hit/miss rate counters
   - Throughput counters (paychecks processed/failed)

### Configuration

```yaml
# application.yml (payroll-worker-service)
cache:
  enabled: true                # Enable/disable caching (default: true)
  tax-ttl-hours: 24           # Tax context cache TTL (default: 24 hours)
  labor-ttl-hours: 24         # Labor standards cache TTL (default: 24 hours)

spring:
  data:
    redis:
      host: redis              # Redis host (default: redis in Docker)
      port: 6379               # Redis port (default: 6379)
```

### Environment Variables

```bash
# docker-compose.redis.yml uses these
CACHE_ENABLED=true                    # Enable caching (default: true)
CACHE_TAX_TTL_HOURS=24               # Tax cache TTL (default: 24)
CACHE_LABOR_TTL_HOURS=24             # Labor cache TTL (default: 24)
SPRING_DATA_REDIS_HOST=redis          # Redis host
SPRING_DATA_REDIS_PORT=6379           # Redis port
```

## Deployment

### With Docker Compose

```bash
# Start with Redis caching + PgBouncer
docker compose \
  -f docker-compose.yml \
  -f docker-compose.pgbouncer.yml \
  -f docker-compose.redis.yml \
  up -d

# Scale workers
docker compose up -d --scale payroll-worker-service=8
```

### Disabling Cache

```bash
# Environment variable
CACHE_ENABLED=false docker compose up -d

# Or modify docker-compose.redis.yml
```

## Benchmarking

### Quick Start

```bash
# Run Phase 3 benchmark (10K employees, 4/8/16 workers, 3 trials each)
./benchmarks/run-phase3-bench.sh
```

### Advanced Usage

```bash
# Custom employee count and worker configurations
EMPLOYEE_COUNT=50000 \
WORKER_REPLICAS_CSV="4,8,12,16" \
TRIALS=5 \
./benchmarks/run-phase3-bench.sh

# Full benchmark with Redis enabled
BENCH_ENABLE_REDIS=true \
EMPLOYEE_COUNT=100000 \
WORKER_REPLICAS_CSV="8,16" \
./benchmarks/run-parallel-payrun-bench.sh
```

### Benchmark Outputs

Results are written to `/tmp/us-payroll-phase3-bench/`:
- `summary.csv` - Performance metrics per worker count
- `summary.md` - Human-readable markdown report
- `k6-*.json` - k6 detailed metrics
- `metrics-*.csv` - Database and system metrics

## Monitoring

### Metrics Endpoints

```bash
# Worker service metrics (replace port with actual port)
curl http://localhost:8088/actuator/prometheus

# Key metrics to monitor:
# - uspayroll_worker_paycheck_computation_time_seconds (histogram)
# - uspayroll_cache_tax_hit_total
# - uspayroll_cache_tax_miss_total
# - uspayroll_cache_labor_hit_total
# - uspayroll_cache_labor_miss_total
# - uspayroll_worker_paychecks_processed_total
```

### Cache Statistics

```bash
# Connect to Redis and check stats
docker exec -it us-payroll-platform-redis-1 redis-cli INFO stats

# View cached keys
docker exec -it us-payroll-platform-redis-1 redis-cli KEYS "tax:*"
docker exec -it us-payroll-platform-redis-1 redis-cli KEYS "labor:*"

# Check memory usage
docker exec -it us-payroll-platform-redis-1 redis-cli INFO memory
```

## Expected Performance Impact

### Without Caching (Baseline)
- 60K employees: 3.6 minutes (278 emp/sec) with 4 workers
- Each employee makes 1 tax service call + 1 labor service call
- Total: 120K service calls for 60K employees

### With Redis Caching (Phase 3)
- First employee in payrun: Cache miss, fetches from service
- Subsequent employees (same employer/date): Cache hit
- Expected: 1-2 service calls total (instead of 120K)
- **Impact**: 30-50% reduction in per-employee processing time
- **Throughput**: 400-500 emp/sec with 8 workers (vs. 278 with 4)

### With PgBouncer + Redis (Phase 3 Complete)
- 8 workers: 2-4x throughput improvement over 4 workers
- 16 workers: Up to 8x throughput improvement (if not CPU-bound)
- 100K employees: < 5 minutes
- 500K employees: 8-10 minutes (goal: < 10 minutes)

## Cache Hit Rate Analysis

### Expected Patterns

For a typical 60K employee payrun with a single employer:
- First employee: 2 cache misses (tax + labor)
- Employees 2-60,000: 2 cache hits each
- **Hit rate**: 99.997% (119,998 hits / 120,000 requests)

For multi-employer payruns or employees in different states:
- Cache misses increase per unique (employer, date, state) combination
- Still expect >95% hit rate for large payruns

### Monitoring Hit Rates

```bash
# Check cache metrics from Prometheus endpoint
curl -s http://localhost:8088/actuator/prometheus | grep uspayroll_cache

# Calculate hit rate
# hit_rate = hits / (hits + misses)
```

## Troubleshooting

### Cache Not Working

1. Check Redis is running:
   ```bash
   docker ps | grep redis
   docker logs us-payroll-platform-redis-1
   ```

2. Verify worker service can connect:
   ```bash
   docker logs us-payroll-platform-payroll-worker-service-1 | grep -i redis
   ```

3. Check cache configuration:
   ```bash
   curl http://localhost:8088/actuator/env | jq '.propertySources[] | select(.name | contains("redis"))'
   ```

### Low Hit Rate

1. Check cache TTL configuration (may be too short)
2. Verify cache keys are stable across requests
3. Check Redis memory eviction (LRU may be evicting too aggressively)

### Performance Not Improving

1. Verify cache is enabled: `CACHE_ENABLED=true`
2. Check hit rate is >90% (see above)
3. Monitor service call latencies (tax/labor services may be slow)
4. Check worker CPU utilization (may be CPU-bound, not service-bound)

## Architecture Decisions

### Why Decorator Pattern?

- Transparent to existing code (no changes to business logic)
- Easy to enable/disable via configuration
- Preserves existing client interfaces
- Allows for gradual rollout and A/B testing

### Why 24-Hour TTL?

- Tax rules rarely change mid-day
- Labor standards are statutory (change annually)
- 24-hour window allows overnight data refreshes
- Can be reduced if needed for compliance

### Why String Serialization?

- Tax and labor domain types are already Jackson-serializable
- Simple to implement and debug
- Avoids complex Redis serialization configuration
- Cache keys are human-readable

## Future Enhancements

1. **Cache Warming**: Pre-populate cache for known employer/date combinations
2. **Cache Invalidation**: Webhook or pub/sub to invalidate when tax rules change
3. **Multi-Level Cache**: Local in-memory cache + Redis for multi-worker efficiency
4. **Cache Compression**: Reduce memory footprint for large tax contexts
5. **Metrics Dashboard**: Grafana dashboard for cache hit rates and performance

## References

- [Scale to Hundreds of Thousands](./scale-to-hundreds-of-thousands.md)
- [Benchmark README](../../benchmarks/README.md)
- [Phase 3 Implementation Plan](warp://plan/43222972-053a-4814-b471-4b13202651f3)
