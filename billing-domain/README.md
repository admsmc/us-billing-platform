# Billing Domain

**Status**: 🚧 Active Development (Phase 3)

This module contains the core billing calculation logic for utility billing, following the functional-core architecture pattern from the original payroll platform.

## Purpose

The billing domain is designed as a pure functional core that handles:
- Usage calculation (meter reads → consumption)
- Rate application (tariffs, tiered rates, time-of-use)
- Charge aggregation (usage charges, demand charges, regulatory fees)
- Bill computation (customer usage → final bill amount)

## Architecture

### Models (`billing.model.*`)
- `CustomerSnapshot`: Customer account state at billing time
- `MeterRead`: Raw meter reading data
- `BillingPeriod`: Billing cycle definition
- `UsageBlock`: Consumption within a rate tier
- `ChargeLine`: Individual charge (usage, demand, regulatory)
- `BillResult`: Final bill with all charges
- `AccountBalance`: Prepaid/postpaid balance tracking
- `ServiceTypes`: Utility service definitions (electric, gas, water, etc.)
- `RegulatorySurcharges`: PUC-mandated fees and surcharges

### Engine Components (`billing.engine.*`)
- `BillingEngine`: Main orchestrator for bill calculation
- `UsageCalculator`: Converts meter reads to consumption
- `RateEngine`: Applies tariff schedules to usage
- `ChargeAggregator`: Sums charges, applies taxes/fees
- `PrepaidBillingEngine`: Specialized engine for prepaid accounts
- `RealTimeUsageEngine`: Handles real-time usage monitoring

## Phase 3 Migration Plan

### Current State
- ✅ Initial billing models defined
- ✅ Basic engine interfaces created
- ✅ Michigan regulatory charge examples
- ⚠️ **Legacy dependency**: `billing-worker-service` and `billing-orchestrator-service` still use `payroll-domain`

### Migration Goals
1. **Complete billing domain implementation**:
   - Finish `BillingEngine.calculateBill()` logic
   - Implement tariff application (tiered, TOU, demand)
   - Add regulatory fee calculators for different jurisdictions
   - Build usage estimation algorithms

2. **Migrate orchestrator service**:
   - Update `billing-orchestrator-service` to call `BillingEngine` instead of `PayrollEngine`
   - Replace paycheck-centric workflows with bill-centric workflows
   - Update HTTP endpoints (payruns → billing cycles, paychecks → bills)

3. **Migrate worker service**:
   - Update `BillingComputationService` (formerly `PayrollRunService`) to use `BillingEngine`
   - Replace HR/Tax/Labor clients with Customer/Rate/Regulatory clients
   - Update queue-driven processing for billing cycles

4. **Remove legacy modules**:
   - Delete `payroll-domain/`
   - Delete `payroll-jackson/`, `payroll-jackson-spring/`
   - Clean up any remaining payroll-specific packages

## Domain Layout Rules

To keep the domain modular and avoid god files:

- **All new domain model types** (entities, value objects, DTOs, etc.) **must live under**
  `com.example.usbilling.billing.model.*`
- **All new engine logic** (orchestration, calculators, helpers) **must live under**
  `com.example.usbilling.billing.engine.*`
- **Do NOT add new top-level Kotlin files** directly under `billing-domain/src/main/kotlin/*.kt`
  in the default package
- Prefer small, focused files grouped by concern (e.g. `ServiceTypes.kt`, `RegulatoryRules.kt`,
  `BillingEngine.kt`) rather than monolithic files

## Testing Strategy

- Unit tests for pure calculation logic (rate application, charge aggregation)
- Golden tests for specific tariff scenarios (e.g., Michigan tiered rates)
- Integration tests will live in service modules (orchestrator, worker)

## Dependencies

The billing domain should remain framework-agnostic and depend only on:
- `shared-kernel` (UtilityId, CustomerId, Money, etc.)
- Port interfaces (e.g., `RateCatalog`, `RegulatoryRulesProvider`)

It must NOT depend on:
- Spring Framework
- HTTP/REST concerns
- Database access
- Messaging/events

Service boundaries are enforced via port interfaces that are implemented by service modules.
