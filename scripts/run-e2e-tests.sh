#!/usr/bin/env bash
set -euo pipefail

# Run E2E tests for billing platform
#
# This script:
# 1. Starts all billing services via docker-compose
# 2. Waits for services to be healthy
# 3. Runs the E2E test suite
# 4. Tears down services (optional)
#
# Usage:
#   ./scripts/run-e2e-tests.sh [--keep-running]

KEEP_RUNNING=false

for arg in "$@"; do
  case $arg in
    --keep-running)
      KEEP_RUNNING=true
      shift
      ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

echo "==> Starting billing platform services..."
docker compose -f docker-compose.yml -f docker-compose.billing.yml up -d

echo ""
echo "==> Waiting for services to be healthy..."

# Wait for postgres
echo "Waiting for postgres..."
timeout 60 bash -c 'until docker compose -f docker-compose.yml -f docker-compose.billing.yml ps postgres | grep -q "healthy"; do sleep 2; done'

# Wait for each billing service
services=(
  "customer-service"
  "rate-service"
  "regulatory-service"
  "billing-worker-service"
  "billing-orchestrator-service"
)

for service in "${services[@]}"; do
  echo "Waiting for $service..."
  timeout 90 bash -c "until docker compose -f docker-compose.yml -f docker-compose.billing.yml ps $service | grep -q 'healthy'; do sleep 3; done" || {
    echo "ERROR: $service failed to become healthy"
    docker compose -f docker-compose.yml -f docker-compose.billing.yml logs "$service"
    exit 1
  }
done

echo ""
echo "==> All services are healthy!"
echo ""
echo "Service endpoints:"
echo "  customer-service: http://localhost:8081"
echo "  rate-service: http://localhost:8082"
echo "  regulatory-service: http://localhost:8083"
echo "  billing-worker-service: http://localhost:8084"
echo "  billing-orchestrator-service: http://localhost:8085"
echo ""

echo "==> Running E2E tests..."
./scripts/gradlew-java21.sh --no-daemon :e2e-tests:test --info || TEST_EXIT_CODE=$?

if [ "${TEST_EXIT_CODE:-0}" -ne 0 ]; then
  echo ""
  echo "ERROR: E2E tests failed!"
  echo ""
  echo "Service logs:"
  for service in "${services[@]}"; do
    echo ""
    echo "==> $service logs:"
    docker compose -f docker-compose.yml -f docker-compose.billing.yml logs --tail=50 "$service"
  done
  
  if [ "$KEEP_RUNNING" = false ]; then
    echo ""
    echo "==> Tearing down services..."
    docker compose -f docker-compose.yml -f docker-compose.billing.yml down -v
  fi
  
  exit "${TEST_EXIT_CODE}"
fi

echo ""
echo "==> E2E tests passed!"

if [ "$KEEP_RUNNING" = false ]; then
  echo ""
  echo "==> Tearing down services..."
  docker compose -f docker-compose.yml -f docker-compose.billing.yml down -v
  echo "Services stopped and volumes removed."
else
  echo ""
  echo "==> Services are still running (--keep-running flag)"
  echo "To stop: docker compose -f docker-compose.yml -f docker-compose.billing.yml down -v"
fi

echo ""
echo "✅ E2E test run complete!"
