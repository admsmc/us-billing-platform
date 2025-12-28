#!/usr/bin/env bash
set -euo pipefail

# Seed test data into billing platform services
#
# This script creates sample customers, meters, billing periods, and rates
# for manual testing and development.
#
# Prerequisites: Services must be running via docker-compose
#
# Usage:
#   ./scripts/seed-billing-test-data.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

CUSTOMER_SERVICE="http://localhost:8081"
RATE_SERVICE="http://localhost:8082"

echo "==> Seeding billing platform test data..."
echo ""

# Create utility
UTILITY_ID="UTIL-DEV-001"

# Create customer
echo "Creating customer..."
CUSTOMER_RESPONSE=$(curl -s -X POST "$CUSTOMER_SERVICE/utilities/$UTILITY_ID/customers" \
  -H "Content-Type: application/json" \
  -d '{
    "utilityId": "UTIL-DEV-001",
    "accountNumber": "ACCT-TEST-001",
    "name": "Test Customer - Residential",
    "serviceAddress": "456 Oak Ave | Unit 2 | Ann Arbor | MI | 48104",
    "mailingAddress": "456 Oak Ave | Unit 2 | Ann Arbor | MI | 48104",
    "status": "ACTIVE",
    "accountBalance": 0
  }')

CUSTOMER_ID=$(echo "$CUSTOMER_RESPONSE" | jq -r '.customerId')
echo "Created customer: $CUSTOMER_ID"

# Create electric meter
echo "Creating electric meter..."
METER_RESPONSE=$(curl -s -X POST "$CUSTOMER_SERVICE/utilities/$UTILITY_ID/customers/$CUSTOMER_ID/meters" \
  -H "Content-Type: application/json" \
  -d '{
    "meterNumber": "MTR-ELEC-001",
    "serviceType": "ELECTRIC",
    "installDate": "2024-01-01",
    "status": "ACTIVE"
  }')

METER_ID=$(echo "$METER_RESPONSE" | jq -r '.meterId')
echo "Created meter: $METER_ID"

# Create billing period
echo "Creating billing period..."
BILLING_PERIOD_RESPONSE=$(curl -s -X POST "$CUSTOMER_SERVICE/utilities/$UTILITY_ID/billing-periods" \
  -H "Content-Type: application/json" \
  -d "{
    \"customerId\": \"$CUSTOMER_ID\",
    \"startDate\": \"2025-01-01\",
    \"endDate\": \"2025-01-31\",
    \"dueDate\": \"2025-02-15\",
    \"status\": \"OPEN\"
  }")

BILLING_PERIOD_ID=$(echo "$BILLING_PERIOD_RESPONSE" | jq -r '.billingPeriodId')
echo "Created billing period: $BILLING_PERIOD_ID"

# Create meter reads
echo "Creating opening meter read..."
curl -s -X POST "$CUSTOMER_SERVICE/utilities/$UTILITY_ID/meter-reads" \
  -H "Content-Type: application/json" \
  -d "{
    \"meterId\": \"$METER_ID\",
    \"readDate\": \"2025-01-01\",
    \"readValue\": 5000.0,
    \"readType\": \"OPENING\",
    \"qualityCode\": \"ACTUAL\"
  }" > /dev/null

echo "Creating closing meter read..."
curl -s -X POST "$CUSTOMER_SERVICE/utilities/$UTILITY_ID/meter-reads" \
  -H "Content-Type: application/json" \
  -d "{
    \"meterId\": \"$METER_ID\",
    \"readDate\": \"2025-01-31\",
    \"readValue\": 5750.0,
    \"readType\": \"CLOSING\",
    \"qualityCode\": \"ACTUAL\"
  }" > /dev/null

echo "Created meter reads (750 kWh usage)"

# Create second customer (commercial)
echo ""
echo "Creating commercial customer..."
CUSTOMER2_RESPONSE=$(curl -s -X POST "$CUSTOMER_SERVICE/utilities/$UTILITY_ID/customers" \
  -H "Content-Type: application/json" \
  -d '{
    "utilityId": "UTIL-DEV-001",
    "accountNumber": "ACCT-TEST-002",
    "name": "Test Customer - Commercial",
    "serviceAddress": "789 Business Blvd | Suite 100 | Detroit | MI | 48226",
    "mailingAddress": "789 Business Blvd | Suite 100 | Detroit | MI | 48226",
    "status": "ACTIVE",
    "accountBalance": 0
  }')

CUSTOMER2_ID=$(echo "$CUSTOMER2_RESPONSE" | jq -r '.customerId')
echo "Created commercial customer: $CUSTOMER2_ID"

# Create gas meter for commercial customer
echo "Creating gas meter..."
METER2_RESPONSE=$(curl -s -X POST "$CUSTOMER_SERVICE/utilities/$UTILITY_ID/customers/$CUSTOMER2_ID/meters" \
  -H "Content-Type: application/json" \
  -d '{
    "meterNumber": "MTR-GAS-001",
    "serviceType": "GAS",
    "installDate": "2024-01-01",
    "status": "ACTIVE"
  }')

METER2_ID=$(echo "$METER2_RESPONSE" | jq -r '.meterId')
echo "Created gas meter: $METER2_ID"

echo ""
echo "==> Test data seeded successfully!"
echo ""
echo "Summary:"
echo "  Utility ID: $UTILITY_ID"
echo "  Customer 1 (Residential): $CUSTOMER_ID"
echo "    - Electric Meter: $METER_ID"
echo "    - Billing Period: $BILLING_PERIOD_ID"
echo "    - Usage: 750 kWh"
echo "  Customer 2 (Commercial): $CUSTOMER2_ID"
echo "    - Gas Meter: $METER2_ID"
echo ""
echo "You can now test the billing workflow with these customers!"
