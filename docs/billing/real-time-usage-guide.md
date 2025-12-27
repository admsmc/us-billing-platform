# Real-Time Usage Reporting and Periodic Billing

## Overview

The platform supports **two distinct but complementary billing workflows**:

1. **Real-Time Usage Reporting** - Mid-cycle usage tracking and bill projections for customer portals
2. **Periodic Billing** - End-of-cycle final bill generation based on designated service periods

Both systems use the same rate structures and calculation logic, ensuring consistency between estimates and final bills.

## Architecture

### Separation of Concerns

```
┌─────────────────────────────────────────────────────────┐
│                    Customer Portal                       │
│             (Real-Time Usage Dashboard)                  │
└───────────────────────┬─────────────────────────────────┘
                        │
                        ▼
        ┌───────────────────────────────┐
        │  RealTimeUsageEngine          │
        │  - Period-to-date usage       │
        │  - Bill projections           │
        │  - Daily usage trends         │
        │  - Interval data (15-min)     │
        └───────────────────────────────┘
        
        
┌─────────────────────────────────────────────────────────┐
│                    Billing Operations                    │
│              (End-of-Period Bill Run)                    │
└───────────────────────┬─────────────────────────────────┘
                        │
                        ▼
        ┌───────────────────────────────┐
        │  BillingEngine                │
        │  - Final bill calculation     │
        │  - Regulatory surcharges      │
        │  - Account balance updates    │
        │  - Bill generation            │
        └───────────────────────────────┘
```

## Real-Time Usage Reporting

### Purpose
- Provide customers with mid-cycle usage visibility
- Project estimated bills before billing period ends
- Enable budget tracking and usage alerts
- Support customer self-service and conservation

### Key Features

**1. Usage Snapshots**
```kotlin
val snapshot = RealTimeUsageEngine.generateSnapshot(
    utilityId = utilityId,
    customerId = customerId,
    serviceType = ServiceType.ELECTRIC,
    currentPeriod = activeBillingPeriod,
    dailyUsageHistory = last30DaysUsage,
    currentMeterRead = latestMeterReading,
    periodStartMeterRead = periodStartReading,
    tariff = electricTariff
)

// Returns:
// - usageToDate: 450 kWh (15 days into 30-day period)
// - projectedUsage: 900 kWh (estimated by end of period)
// - projectedBill: $108.50 (estimated total)
// - confidence: 0.50 (50% through period)
```

**2. Multi-Service Dashboard**
```kotlin
val dashboard = RealTimeUsageEngine.generateDashboard(
    utilityId = utilityId,
    customerId = customerId,
    serviceSnapshots = listOf(
        electricSnapshot,
        waterSnapshot,
        wastewaterSnapshot,
        broadbandSnapshot
    ),
    budgetAmount = Money(30000) // $300 budget
)

// Shows combined projected bill across all services
// Budget tracking: on/over/under budget
```

**3. Interval Data**
```kotlin
val intervalData = IntervalUsage(
    meterId = "ELEC-001",
    serviceType = ServiceType.ELECTRIC,
    intervalStart = Instant.parse("2025-12-27T14:00:00Z"),
    intervalEnd = Instant.parse("2025-12-27T14:15:00Z"),
    usage = 2.5, // kWh in 15-minute interval
    usageUnit = UsageUnit.KWH,
    demand = 10.0, // kW peak demand
    cost = Money(30) // $0.30 estimated
)
```

### Projection Methods

The system supports multiple projection algorithms:

- **DAILY_AVERAGE**: Simple average of usage per day × remaining days
- **WEIGHTED_AVERAGE**: Recent days weighted more heavily
- **YEAR_OVER_YEAR**: Historical pattern from same period last year
- **ML_MODEL**: Machine learning prediction (future enhancement)
- **DEGREE_DAY_ADJUSTED**: Weather-normalized projection

### Update Frequency

Real-time usage can be updated at different frequencies depending on meter infrastructure:

| Meter Type | Update Frequency | Use Case |
|------------|------------------|----------|
| AMI (Smart Meter) | 15-minute intervals | Real-time portal, alerts |
| AMR (Drive-by) | Daily | Next-day usage reporting |
| Manual Read | Monthly | Periodic billing only |

## Periodic Billing

### Purpose
- Generate final, official bills at end of designated billing periods
- Apply regulatory surcharges and contributions
- Update account balances
- Trigger payment processing and collections

### Billing Periods

Configured per customer with:
- **Start/End Dates**: Define the service period
- **Bill Date**: When bill is generated
- **Due Date**: Payment deadline
- **Frequency**: MONTHLY, BIMONTHLY, QUARTERLY, ANNUAL

```kotlin
val billingPeriod = BillingPeriod(
    id = "202512",
    utilityId = utilityId,
    startDate = LocalDate.of(2025, 12, 1),
    endDate = LocalDate.of(2025, 12, 31),
    billDate = LocalDate.of(2025, 12, 31),
    dueDate = LocalDate.of(2026, 1, 20),
    frequency = BillingFrequency.MONTHLY
)
```

### Bill Generation

```kotlin
val billResult = BillingEngine.calculateBill(input)
// or
val billResult = BillingEngine.calculateMultiServiceBill(input)

// Returns complete bill with:
// - All line item charges
// - Regulatory surcharges
// - Voluntary contributions
// - Account balance (before/after)
// - Total amount due
```

## Data Flow

### Real-Time Usage Flow

```
1. Meter Data Collection
   ↓
2. Interval Data Aggregation (15-min → hourly → daily)
   ↓
3. RealTimeUsageEngine.generateSnapshot()
   ↓
4. Customer Portal Display
   ↓
5. Repeat every 15min/hour/day
```

### Periodic Billing Flow

```
1. Billing Period Closes
   ↓
2. Final Meter Reads Collected
   ↓
3. BillingEngine.calculateBill()
   ↓
4. Bill Review & Approval
   ↓
5. Bill Delivery (print/email/portal)
   ↓
6. Payment Processing
```

## Example: Mid-Cycle vs. Final Bill

### Mid-Cycle Snapshot (Day 15 of 30)

**Real-Time Usage Report:**
```
Electric Service - Period to Date (Dec 1-15)
─────────────────────────────────────────────
Usage to Date:           450 kWh
Days Elapsed:            15 of 30 days
Average Daily Usage:     30 kWh/day

Estimated Charges to Date:
  Readiness to Serve:    $7.50 (prorated)
  Usage (450 kWh):       $54.00
  ─────────────────────
  Subtotal:              $61.50

Projected Bill (Full Period):
  Readiness to Serve:    $15.00
  Usage (900 kWh):       $108.00
  Surcharges:            $8.00
  ─────────────────────
  PROJECTED TOTAL:       $131.00
  Confidence:            50%
```

### Final Bill (End of Period)

**Official Bill Generated Dec 31:**
```
Electric Service - Billing Period (Dec 1-31)
─────────────────────────────────────────────
Meter Readings:
  Previous:              45,200 kWh (Dec 1)
  Current:               46,050 kWh (Dec 31)
  Usage:                 850 kWh

Charges:
  Readiness to Serve     $15.00
  Usage Tier 1 (500 kWh @ $0.10)   $50.00
  Usage Tier 2 (350 kWh @ $0.12)   $42.00
  PSCR Surcharge         $1.06
  SAF Surcharge          $7.00
  ─────────────────────
  TOTAL AMOUNT DUE:      $115.06
  Due Date:              January 20, 2026
```

**Note:** Final bill ($115.06) differs from mid-cycle projection ($131.00) because:
- Actual usage (850 kWh) was less than projected (900 kWh)
- Final bill uses exact meter reads vs. daily estimates
- Surcharges calculated on actual consumption

## Implementation Guidelines

### For Real-Time Portals

1. **Cache aggressively** - Don't recalculate on every page load
2. **Update frequency**: 15-min for AMI, hourly for most customers
3. **Show confidence levels** - Help customers understand estimate accuracy
4. **Trend visualization** - Show daily usage patterns
5. **Alerts** - Notify when projected bill exceeds budget

### For Periodic Billing

1. **Final meter reads** - Use ACTUAL readings when available
2. **Validation** - Check for anomalies before finalizing
3. **Audit trail** - Log all bill calculations
4. **Regulatory compliance** - Apply all required surcharges
5. **Account updates** - Update balance, payment history

## API Examples

### Real-Time Usage Endpoint

```http
GET /api/v1/customers/{customerId}/usage/real-time?service=ELECTRIC

Response:
{
  "customerId": "customer-12345",
  "serviceType": "ELECTRIC",
  "snapshotTime": "2025-12-27T10:30:00Z",
  "periodToDate": {
    "daysElapsed": 15,
    "daysRemaining": 15,
    "usageToDate": 450.0,
    "usageUnit": "KWH",
    "estimatedCharges": {"amount": 6150}
  },
  "projectedBill": {
    "projectedTotal": {"amount": 13100},
    "projectedUsage": 900.0,
    "confidenceLevel": 0.50
  }
}
```

### Periodic Bill Endpoint

```http
POST /api/v1/billing/calculate-bill

Request:
{
  "billPeriod": {
    "startDate": "2025-12-01",
    "endDate": "2025-12-31"
  },
  "meterReads": [...],
  "rateTariff": {...}
}

Response:
{
  "billId": "BILL-202512-12345",
  "totalCharges": {"amount": 11506},
  "amountDue": {"amount": 11506},
  "charges": [...]
}
```

## Best Practices

### Real-Time Usage

✅ **DO:**
- Clearly label estimates as "projected" or "estimated"
- Show calculation methodology and confidence levels
- Provide historical comparison (vs. last month, last year)
- Enable usage alerts and budget tracking
- Update frequently (15-min to hourly)

❌ **DON'T:**
- Present estimates as final bills
- Hide projection methodology from customers
- Ignore data quality issues
- Over-promise accuracy early in billing period

### Periodic Billing

✅ **DO:**
- Use actual meter reads whenever possible
- Validate data before finalizing bills
- Apply all regulatory requirements
- Maintain complete audit trail
- Send bills consistently on schedule

❌ **DON'T:**
- Skip validation steps for speed
- Modify bills after delivery without proper process
- Ignore estimated read flags
- Bill before period actually ends

## Testing

```bash
# Test real-time usage engine
./gradlew :billing-domain:test --tests "*RealTimeUsage*"

# Test periodic billing engine
./gradlew :billing-domain:test --tests "*BillingEngine*"
```

## References

- [AMI/Smart Meter Integration](https://www.energy.gov/oe/advanced-metering-infrastructure)
- [Customer Usage Portals Best Practices](https://www.naruc.org)
- [Budget Billing Programs](https://www.eia.gov)
