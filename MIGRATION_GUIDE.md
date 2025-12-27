# Migration Guide: Payroll Domain → Billing Domain

## Status: Phase 3B Complete ✅, Phase 3C Ready for Execution

**Current Commit:** Phase 3B complete (f4e68af)
- ✅ billing-domain enhanced with TOU hourly support, regulatory charge catalog, usage validation
- ⏸️ payroll-domain still present (13 modules depend on it)
- ⏸️ Worker/orchestrator services still use PayrollEngine

## Overview

This platform was forked from `us-payroll-platform` and is being migrated to a utility billing platform. Phase 3B successfully built out production-grade billing capabilities. Phase 3C requires migrating all services from payroll concepts (employees, paychecks, withholding) to billing concepts (customers, bills, rates).

## Why Phase 3C is a Multi-Day Effort

### Scope Analysis
- **100+ files** import from payroll-domain across 13 modules
- **Structural coupling**: API modules use payroll types as interface contracts
- **Complex domain logic**: Tax withholding, garnishments, FLSA compliance deeply embedded

### Module Dependencies
```
payroll-domain (4,208 lines)
├── customer-api (EmployeeSnapshot, PayPeriod interfaces)
├── customer-client (HrClient)
├── customer-service (12 files: pay periods, garnishments, employee data)
├── rate-api (TaxContext, TaxContextProvider)
├── rate-service (20 test files with tax calculation logic)
├── rate-impl (Tax rule persistence)
├── rate-catalog-ports (TaxRuleRecord)
├── regulatory-api (LaborStandardsContext)
├── regulatory-service (Labor standards implementation)
├── billing-worker-service (WorkerPaycheckComputationService - 193 lines)
├── billing-orchestrator-service (PaycheckComputationService - 223 lines)
├── billing-jackson (Payroll type serializers)
└── billing-benchmarks (Paycheck computation benchmarks)
```

## Phase 3C Execution Plan

### Step 1: Type Migration (Day 1-2)

#### 1.1 Create billing-domain equivalents

**CustomerSnapshot** (replaces EmployeeSnapshot)
```kotlin
// Location: billing-domain/src/main/kotlin/com/example/usbilling/billing/model/CustomerSnapshot.kt
data class CustomerSnapshot(
    val utilityId: UtilityId,
    val customerId: CustomerId,
    val serviceAddress: ServiceAddress,
    val billingAddress: Address,
    val accountStatus: AccountStatus,
    val serviceTypes: Set<ServiceType>,
    val rateClass: RateClass, // RESIDENTIAL, COMMERCIAL, INDUSTRIAL
    val enrolledPrograms: Set<String> = emptySet(), // Low-income assistance, budget billing, etc.
    val accountBalance: Money,
    val lastPaymentDate: LocalDate? = null,
    val paymentHistory: PaymentHistory? = null,
    val customerSince: LocalDate,
    val serviceState: String,
    val serviceCity: String? = null
)

data class ServiceAddress(
    val street: String,
    val city: String,
    val state: String,
    val zipCode: String
)

enum class AccountStatus {
    ACTIVE,
    SUSPENDED,
    DISCONNECTED,
    PENDING_CONNECTION
}

enum class RateClass {
    RESIDENTIAL,
    COMMERCIAL_SMALL,
    COMMERCIAL_LARGE,
    INDUSTRIAL
}
```

**BillingPeriod** (replaces PayPeriod)
```kotlin
// This already exists! billing-domain/src/main/kotlin/com/example/usbilling/billing/model/CommonTypes.kt
// No action needed - already has BillingPeriod with:
// - id, utilityId, startDate, endDate, billDate, dueDate, frequency
```

**RateContext** (replaces TaxContext)
```kotlin
// Location: billing-domain/src/main/kotlin/com/example/usbilling/billing/model/RateContext.kt
data class RateContext(
    val utilityId: UtilityId,
    val serviceState: String,
    val rateSchedules: Map<ServiceType, RateTariff>,
    val regulatoryCharges: List<RegulatoryCharge>,
    val effectiveDate: LocalDate
)
```

**RegulatoryContext** (replaces LaborStandardsContext)
```kotlin
// Location: billing-domain/src/main/kotlin/com/example/usbilling/billing/model/RegulatoryContext.kt
data class RegulatoryContext(
    val utilityId: UtilityId,
    val jurisdiction: String,
    val regulatoryCharges: List<RegulatoryCharge>,
    val effectiveDate: LocalDate
)
```

#### 1.2 Update API modules

**customer-api/HrPorts.kt** → **customer-api/CustomerPorts.kt**
```kotlin
// Replace:
interface EmployeeSnapshotProvider {
    fun getEmployeeSnapshot(...): EmployeeSnapshot?
}
interface PayPeriodProvider {
    fun getPayPeriod(...): PayPeriod?
}

// With:
interface CustomerSnapshotProvider {
    fun getCustomerSnapshot(utilityId: UtilityId, customerId: CustomerId, asOfDate: LocalDate): CustomerSnapshot?
}
interface BillingPeriodProvider {
    fun getBillingPeriod(utilityId: UtilityId, billingPeriodId: String): BillingPeriod?
    fun findBillingPeriodByBillDate(utilityId: UtilityId, billDate: LocalDate): BillingPeriod?
}
```

**rate-api/TaxContextProvider.kt** → **rate-api/RateContextProvider.kt**
```kotlin
interface RateContextProvider {
    fun getRateContext(utilityId: UtilityId, asOfDate: LocalDate, serviceState: String): RateContext
}
```

**regulatory-api/LaborStandardsContextProvider.kt** → **regulatory-api/RegulatoryContextProvider.kt**
```kotlin
interface RegulatoryContextProvider {
    fun getRegulatoryContext(
        utilityId: UtilityId,
        asOfDate: LocalDate,
        jurisdiction: String
    ): RegulatoryContext
}
```

#### 1.3 Update client modules

**customer-client/HrClient.kt** → **customer-client/CustomerClient.kt**
```kotlin
class CustomerClient(
    private val customerApiUrl: String,
    private val restTemplate: RestTemplate
) {
    fun getCustomerSnapshot(utilityId: UtilityId, customerId: CustomerId, asOfDate: LocalDate): CustomerSnapshot? {
        // HTTP call to customer-service
    }
    
    fun getBillingPeriod(utilityId: UtilityId, billingPeriodId: String): BillingPeriod? {
        // HTTP call to customer-service
    }
    
    fun getMeterReads(utilityId: UtilityId, customerId: CustomerId, billingPeriodId: String): List<MeterReadPair> {
        // HTTP call to meter-reading-service
    }
}
```

### Step 2: Service Migration (Day 2-3)

#### 2.1 Create BillingComputationService (worker)

**Location:** `billing-worker-service/src/main/kotlin/com/example/usbilling/worker/service/BillingComputationService.kt`

```kotlin
package com.example.usbilling.worker.service

import com.example.usbilling.billing.engine.BillingEngine
import com.example.usbilling.billing.model.*
import com.example.usbilling.messaging.jobs.FinalizeBillingCycleCustomerJob
import com.example.usbilling.shared.*
import com.example.usbilling.worker.client.CustomerClient
import com.example.usbilling.worker.client.RateClient
import com.example.usbilling.worker.client.RegulatoryClient
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class BillingComputationService(
    private val customerClient: CustomerClient,
    private val rateClient: RateClient,
    private val regulatoryClient: RegulatoryClient,
    private val inputFingerprinter: InputFingerprinter,
    private val performanceMetrics: PerformanceMetrics,
) {

    fun computeForFinalizeBillJob(job: FinalizeBillingCycleCustomerJob): BillComputation {
        val startTime = System.nanoTime()
        return try {
            computeForFinalizeBillJobInternal(job)
        } finally {
            val duration = System.nanoTime() - startTime
            performanceMetrics.recordBillComputationTime(duration)
            performanceMetrics.incrementBillsProcessed()
        }
    }

    private fun computeForFinalizeBillJobInternal(job: FinalizeBillingCycleCustomerJob): BillComputation {
        val utilityId = UtilityId(job.utilityId)
        val customerId = CustomerId(job.customerId)

        // Fetch billing period
        val billingPeriod = customerClient.getBillingPeriod(utilityId, job.billingPeriodId)
            ?: error("No billing period '${job.billingPeriodId}' for utility ${job.utilityId}")

        // Fetch customer snapshot
        val customer = customerClient.getCustomerSnapshot(
            utilityId = utilityId,
            customerId = customerId,
            asOfDate = billingPeriod.billDate
        ) ?: error("No customer snapshot for ${job.customerId} as of ${billingPeriod.billDate}")

        // Fetch meter reads
        val meterReads = customerClient.getMeterReads(
            utilityId = utilityId,
            customerId = customerId,
            billingPeriodId = job.billingPeriodId
        )

        // Fetch rate context (tariffs for this customer's service types)
        val rateContext = rateClient.getRateContext(
            utilityId = utilityId,
            asOfDate = billingPeriod.billDate,
            serviceState = customer.serviceState
        )

        // Fetch regulatory context
        val regulatoryContext = regulatoryClient.getRegulatoryContext(
            utilityId = utilityId,
            asOfDate = billingPeriod.billDate,
            jurisdiction = customer.serviceState
        )

        // Build BillInput
        val input = BillInput(
            billId = BillId(job.billId),
            billRunId = BillingCycleId(job.billingCycleId),
            utilityId = utilityId,
            customerId = customerId,
            billPeriod = billingPeriod,
            meterReads = meterReads,
            rateTariff = rateContext.rateSchedules[customer.serviceTypes.first()]!!, // TODO: multi-service
            accountBalance = customer.accountBalance,
            regulatorySurcharges = regulatoryContext.regulatoryCharges
        )

        // Calculate bill using BillingEngine
        val billResult = BillingEngine.calculateBill(input)

        // Create audit trail
        val audit = BillAudit(
            billId = billResult.billId,
            utilityId = utilityId,
            customerId = customerId,
            computedAt = Instant.now(),
            inputFingerprint = inputFingerprinter.stamp(input),
            traceLevel = TraceLevel.AUDIT
        )

        return BillComputation(
            bill = billResult,
            audit = audit
        )
    }
}

data class BillComputation(
    val bill: BillResult,
    val audit: BillAudit
)

data class BillAudit(
    val billId: BillId,
    val utilityId: UtilityId,
    val customerId: CustomerId,
    val computedAt: Instant,
    val inputFingerprint: String,
    val traceLevel: TraceLevel
)

enum class TraceLevel {
    NONE,
    AUDIT,
    DEBUG
}
```

#### 2.2 Create BillingOrchestrationService (orchestrator)

**Location:** `billing-orchestrator-service/src/main/kotlin/com/example/usbilling/orchestrator/service/BillingOrchestrationService.kt`

Similar structure to worker service, but with persistence:

```kotlin
@Service
class BillingOrchestrationService(
    private val customerClient: CustomerClient,
    private val rateClient: RateClient,
    private val regulatoryClient: RegulatoryClient,
    private val billStoreRepository: BillStoreRepository,
    private val billAuditStoreRepository: BillAuditStoreRepository,
    private val inputFingerprinter: InputFingerprinter,
) {
    
    fun computeAndPersistFinalBill(
        utilityId: UtilityId,
        billingCycleId: String,
        billingPeriodId: String,
        runType: BillingCycleType,
        runSequence: Int,
        billId: String,
        customerId: CustomerId,
    ): BillResult {
        val computation = computeBillForCustomer(
            utilityId = utilityId,
            billingCycleId = billingCycleId,
            billingPeriodId = billingPeriodId,
            runType = runType,
            billId = billId,
            customerId = customerId
        )

        val bill = computation.bill

        billStoreRepository.insertFinalBillIfAbsent(
            utilityId = utilityId,
            billId = bill.billId.value,
            billingCycleId = bill.billRunId.value,
            customerId = bill.customerId.value,
            billingPeriodId = bill.billPeriod.id,
            runType = runType.name,
            runSequence = runSequence,
            billDateIso = bill.billPeriod.billDate.toString(),
            amountDue = bill.amountDue.amount,
            version = 1,
            payload = bill
        )

        billAuditStoreRepository.insertAuditIfAbsent(computation.audit)

        return bill
    }

    private fun computeBillForCustomer(...): BillComputation {
        // Similar logic to worker service
        ...
    }
}
```

#### 2.3 Update message handlers

**billing-worker-service** - Update queue consumer:

```kotlin
// Replace:
@RabbitListener(queues = ["finalize-payrun-employee"])
fun handleFinalizePayRunEmployee(@Payload job: FinalizePayRunEmployeeJob) {
    val computation = workerPaycheckComputationService.computeForFinalizeJob(job)
    // ...
}

// With:
@RabbitListener(queues = ["finalize-billing-cycle-customer"])
fun handleFinalizeBillingCycleCustomer(@Payload job: FinalizeBillingCycleCustomerJob) {
    val computation = billingComputationService.computeForFinalizeBillJob(job)
    // ...
}
```

### Step 3: Cleanup (Day 4-5)

#### 3.1 Remove payroll-domain dependencies

Update `build.gradle.kts` for all 13 modules:
```kotlin
// Remove:
implementation(project(":payroll-domain"))

// Add (where needed):
implementation(project(":billing-domain"))
```

#### 3.2 Delete payroll modules

```bash
# Remove from settings.gradle.kts
# Delete:
rm -rf payroll-domain/
rm -rf payroll-jackson/
rm -rf payroll-jackson-spring/  # if exists

# Verify build
./scripts/gradlew-java21.sh build --no-daemon
./scripts/gradlew-java21.sh test --no-daemon
```

#### 3.3 Update benchmarks

Update `billing-benchmarks` to use BillingEngine:
```kotlin
@Benchmark
fun computeBill(state: BillingState) {
    BillingEngine.calculateBill(state.billInput)
}
```

### Step 4: Verification & Testing

#### 4.1 Build verification
```bash
./scripts/gradlew-java21.sh build --no-daemon
```

#### 4.2 Integration tests
- Verify worker service processes bills correctly
- Verify orchestrator service persists bills
- Verify API endpoints return correct responses
- Check message queue processing

#### 4.3 Performance regression testing
- Run billing benchmarks
- Compare throughput with baseline
- Verify no performance degradation

## Risk Mitigation

### Rollback Strategy
1. Keep payroll-domain code in a branch until migration is proven stable
2. Use feature flags for dual-mode operation during transition
3. Monitor error rates and performance metrics closely

### Testing Strategy
1. Create comprehensive integration tests before migration
2. Test each service independently before connecting
3. Use test doubles/mocks during development
4. Run full end-to-end tests before removing payroll-domain

### Incremental Deployment
1. Deploy new services alongside old ones
2. Use feature flags to control which code path is active
3. Gradually shift traffic to new services
4. Monitor metrics and roll back if issues arise

## Timeline Estimate

| Phase | Duration | Deliverables |
|-------|----------|--------------|
| 3C.1: Type Migration | 2 days | New billing types, updated API modules |
| 3C.2: Service Migration | 2-3 days | New worker/orchestrator services |
| 3C.3: Cleanup | 1-2 days | Payroll dependencies removed, tests passing |
| **Total** | **5-7 days** | Full billing platform, payroll-domain deleted |

## Current State (Post-Phase 3B)

✅ **Completed:**
- billing-domain enhanced with production-grade features
- TOU hourly support with HourlyUsageProfile, TouPeriodSchedule
- Regulatory charge repository with multi-state data
- Usage validator for meter read validation and estimation
- Demo endpoints proven functional

⏸️ **Deferred to Phase 3C:**
- Customer/billing type migration
- Worker/orchestrator service migration
- Payroll-domain removal
- Full integration testing

## Next Steps

When ready to execute Phase 3C:
1. Create a feature branch: `git checkout -b feature/phase-3c-complete-migration`
2. Follow this guide step-by-step
3. Commit after each major milestone
4. Run tests frequently to catch issues early
5. Review and merge when complete

## Questions?

See the full plan document at `plan_3ddd65ad.md` for detailed context and analysis.
