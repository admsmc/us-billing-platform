# E2E Test Status & SCD2 Implementation Plan

## Current Status (2025-12-28)

### Test Results
- **1 of 11 tests PASSING** ✅
  - Test 1: "Should create a customer in customer-service" ✅

### Blocking Issues for Remaining Tests

#### 1. Missing Services (Tests 5, 6, 7-11)
**Status**: rate-service and regulatory-service not starting, billing-orchestrator-service not running
- Test 5: GET `/utilities/UTIL-001/rate-context` → 404
- Test 6: GET `/utilities/UTIL-001/regulatory-charges` → 404  
- Tests 7-11: Connection refused to billing-orchestrator on port 8085

**Action**: Start all services:
```bash
docker compose -f docker-compose.billing.yml up -d
```

#### 2. Persistable Interface Issues (Tests 2, 3, 4)
**Status**: MeterEntity, BillingPeriodEntity, MeterReadEntity need Persistable interface
- Test 2: POST create meter → 500 error
- Test 3: POST create billing period → 500 error
- Test 4: POST create meter reads → depends on test 2

**Root cause**: Spring Data JDBC attempts UPDATE instead of INSERT for entities with manually-assigned IDs.

**Solution Applied to CustomerEntity** (working):
```kotlin
@Table("customer")
data class CustomerEntity(
    @Id val customerId: String,
    // ... other fields ...
    @Transient
    @JsonIgnore
    val isNewEntity: Boolean = true
) : Persistable<String> {
    override fun getId(): String = customerId
    @JsonIgnore
    override fun isNew(): Boolean = isNewEntity
}
```

**Action**: Apply same pattern to MeterEntity, BillingPeriodEntity, MeterReadEntity

## SCD2 Bitemporal Implementation Plan

### Background
Current implementation uses simple CRUD schema (V1):
- Tables: `customer`, `meter`, `billing_period`, `meter_read`
- Operations: INSERT, UPDATE, DELETE

User requires **SCD2 append-only pattern** with bitemporal tracking:
- Migrations V014-V019 already define bitemporal tables
- Need to migrate from V1 CRUD to SCD2 append-only

### SCD2 Schema (Already Exists in DB)

#### V014: customer_account_effective
```sql
CREATE TABLE customer_account_effective (
    account_id VARCHAR(36) NOT NULL,
    utility_id VARCHAR(36) NOT NULL,
    account_number VARCHAR(50) NOT NULL,
    customer_name VARCHAR(200) NOT NULL,
    -- Bitemporal columns
    effective_from DATE NOT NULL,
    effective_to DATE NOT NULL,
    system_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    system_to TIMESTAMP NOT NULL DEFAULT '9999-12-31 23:59:59',
    PRIMARY KEY (account_id, effective_from, system_from)
);
```

#### V015: service_point_effective, meter_effective
```sql
CREATE TABLE service_point_effective (
    service_point_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    service_address TEXT NOT NULL,
    -- Bitemporal columns
    effective_from DATE NOT NULL,
    effective_to DATE NOT NULL,
    system_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    system_to TIMESTAMP NOT NULL DEFAULT '9999-12-31 23:59:59',
    PRIMARY KEY (service_point_id, effective_from, system_from)
);

CREATE TABLE meter_effective (
    meter_id VARCHAR(36) NOT NULL,
    service_point_id VARCHAR(36) NOT NULL,
    utility_service_type VARCHAR(20) NOT NULL,
    meter_number VARCHAR(50) NOT NULL,
    -- Bitemporal columns
    effective_from DATE NOT NULL,
    effective_to DATE NOT NULL,
    system_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    system_to TIMESTAMP NOT NULL DEFAULT '9999-12-31 23:59:59',
    PRIMARY KEY (meter_id, effective_from, system_from)
);
```

### Implementation Steps

#### Step 1: Create Bitemporal Entity Classes ✅ (Schema exists, entities needed)
**File**: `customer-service/src/main/kotlin/com/example/usbilling/hr/domain/BitemporalEntities.kt`

```kotlin
@Table("customer_account_effective")
data class CustomerAccountEffective(
    @Id val accountId: String,
    val utilityId: String,
    val accountNumber: String,
    val customerName: String,
    val serviceAddress: String,
    val customerClass: String?,
    val active: Boolean,
    // Bitemporal columns
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate,
    val systemFrom: Instant,
    val systemTo: Instant
)

@Table("meter_effective")
data class MeterEffective(
    @Id val meterId: String,
    val servicePointId: String,
    val utilityServiceType: String,
    val meterNumber: String,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate,
    val systemFrom: Instant,
    val systemTo: Instant
)
```

#### Step 2: Implement Bitemporal Repository Layer
**File**: `customer-service/src/main/kotlin/com/example/usbilling/hr/repository/BitemporalRepositories.kt`

```kotlin
interface CustomerAccountBitemporalRepository : CrudRepository<CustomerAccountEffective, String> {
    
    /**
     * Get current version (system_from <= now AND system_to > now AND effective_from <= asOf AND effective_to > asOf)
     */
    @Query("""
        SELECT * FROM customer_account_effective
        WHERE account_id = :accountId
          AND system_from <= CURRENT_TIMESTAMP
          AND system_to > CURRENT_TIMESTAMP
          AND effective_from <= :asOfDate
          AND effective_to > :asOfDate
        ORDER BY effective_from DESC
        LIMIT 1
    """)
    fun findCurrentVersion(accountId: String, asOfDate: LocalDate): CustomerAccountEffective?
    
    /**
     * Append new version (INSERT only, never UPDATE)
     */
    fun save(entity: CustomerAccountEffective): CustomerAccountEffective
    
    /**
     * Close current system version by setting system_to = now
     */
    @Modifying
    @Query("""
        UPDATE customer_account_effective
        SET system_to = CURRENT_TIMESTAMP
        WHERE account_id = :accountId
          AND system_to = '9999-12-31 23:59:59'::timestamp
    """)
    fun closeCurrentSystemVersion(accountId: String)
}
```

#### Step 3: Update Service Layer for Bitemporal Queries
**File**: `customer-service/src/main/kotlin/com/example/usbilling/hr/service/BitemporalCustomerService.kt`

```kotlin
@Service
class BitemporalCustomerService(
    private val customerAccountRepository: CustomerAccountBitemporalRepository,
    private val meterRepository: MeterBitemporalRepository
) : CustomerSnapshotProvider {
    
    override fun getCustomerSnapshot(
        utilityId: UtilityId,
        customerId: CustomerId,
        asOfDate: LocalDate
    ): CustomerSnapshot? {
        // Query current version as of date
        val customer = customerAccountRepository.findCurrentVersion(
            customerId.value,
            asOfDate
        ) ?: return null
        
        // Get meters current as of date
        val meters = meterRepository.findCurrentVersionsByAccount(
            customerId.value,
            asOfDate
        )
        
        return mapToCustomerSnapshot(customer, meters)
    }
    
    /**
     * Create new customer - append-only operation
     */
    fun createCustomer(request: CreateCustomerRequest): CustomerAccountEffective {
        val accountId = UUID.randomUUID().toString()
        val effectiveFrom = LocalDate.now()
        
        val entity = CustomerAccountEffective(
            accountId = accountId,
            utilityId = request.utilityId,
            accountNumber = request.accountNumber,
            customerName = request.customerName,
            serviceAddress = request.serviceAddress,
            customerClass = request.customerClass,
            active = true,
            effectiveFrom = effectiveFrom,
            effectiveTo = LocalDate.of(9999, 12, 31), // Open-ended
            systemFrom = Instant.now(),
            systemTo = Instant.parse("9999-12-31T23:59:59Z")
        )
        
        return customerAccountRepository.save(entity)
    }
    
    /**
     * Update customer - append new version, close old
     */
    fun updateCustomer(accountId: String, updates: Map<String, Any>): CustomerAccountEffective {
        // 1. Get current version
        val current = customerAccountRepository.findCurrentVersion(accountId, LocalDate.now())
            ?: throw NotFoundException("Account $accountId not found")
        
        // 2. Close current system version
        customerAccountRepository.closeCurrentSystemVersion(accountId)
        
        // 3. Insert new version with updates
        val newVersion = current.copy(
            customerName = updates["customerName"] as? String ?: current.customerName,
            serviceAddress = updates["serviceAddress"] as? String ?: current.serviceAddress,
            systemFrom = Instant.now(),
            systemTo = Instant.parse("9999-12-31T23:59:59Z")
        )
        
        return customerAccountRepository.save(newVersion)
    }
}
```

#### Step 4: Migrate Controllers to Append-Only Operations
**File**: `customer-service/src/main/kotlin/com/example/usbilling/hr/http/BitemporalCustomerController.kt`

```kotlin
@RestController
@RequestMapping("/utilities/{utilityId}")
class BitemporalCustomerController(
    private val bitemporalCustomerService: BitemporalCustomerService
) {
    
    /**
     * Create customer - append-only
     */
    @PostMapping("/customers")
    fun createCustomer(
        @PathVariable utilityId: String,
        @RequestBody request: CreateCustomerRequest
    ): ResponseEntity<CustomerAccountEffective> {
        val customer = bitemporalCustomerService.createCustomer(
            request.copy(utilityId = utilityId)
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(customer)
    }
    
    /**
     * Update customer - append new version
     */
    @PutMapping("/customers/{customerId}")
    fun updateCustomer(
        @PathVariable utilityId: String,
        @PathVariable customerId: String,
        @RequestBody updates: Map<String, Any>
    ): ResponseEntity<CustomerAccountEffective> {
        val updated = bitemporalCustomerService.updateCustomer(customerId, updates)
        return ResponseEntity.ok(updated)
    }
    
    /**
     * Get customer as of date
     */
    @GetMapping("/customers/{customerId}")
    fun getCustomer(
        @PathVariable utilityId: String,
        @PathVariable customerId: String,
        @RequestParam(required = false) asOfDate: String?
    ): ResponseEntity<CustomerAccountEffective> {
        val date = asOfDate?.let { LocalDate.parse(it) } ?: LocalDate.now()
        
        val customer = bitemporalCustomerService.getCustomerAsOf(
            UtilityId(utilityId),
            CustomerId(customerId),
            date
        ) ?: return ResponseEntity.notFound().build()
        
        return ResponseEntity.ok(customer)
    }
}
```

#### Step 5: Update E2E Tests for Bitemporal Pattern
- Tests should expect bitemporal fields in responses
- Update assertions to check `effectiveFrom`, `effectiveTo`, `systemFrom`, `systemTo`
- Consider using fixed dates for deterministic testing

### Migration Strategy

Two possible approaches:

#### Option A: Parallel Implementation (Recommended)
1. Keep existing V1 CRUD endpoints operational
2. Implement new bitemporal endpoints under `/v2/` path prefix
3. Migrate E2E tests to use v2 endpoints
4. Deprecate v1 endpoints after migration complete

#### Option B: In-Place Migration
1. Migrate data from V1 tables to SCD2 tables
2. Update all controllers/services to use SCD2
3. Drop V1 tables after verification

**Recommendation**: Use Option A for safer migration with rollback capability

## Immediate Next Steps (Priority Order)

1. **Fix remaining E2E test blockers** (30 min)
   - Apply Persistable pattern to MeterEntity, BillingPeriodEntity, MeterReadEntity
   - Verify all 5 services are running
   - Clear database and rerun E2E tests
   - Target: Get 4-5 tests passing

2. **Document SCD2 design decision** (15 min)
   - Create ADR (Architecture Decision Record) for bitemporal pattern
   - Define query patterns for "as-of-date" and "as-of-time" lookups
   - Document update workflow (close old, insert new)

3. **Implement bitemporal entity layer** (1-2 hours)
   - Create BitemporalEntities.kt with CustomerAccountEffective, MeterEffective
   - Implement BitemporalRepositories.kt with query methods
   - Add unit tests for repository queries

4. **Implement bitemporal service layer** (2-3 hours)
   - Create BitemporalCustomerService with append-only operations
   - Implement version closing logic
   - Add service-level tests

5. **Create v2 endpoints** (1-2 hours)
   - Implement BitemporalCustomerController under `/v2/`
   - Test with Postman/curl
   - Update E2E tests to use v2 endpoints

6. **Data migration script** (1 hour)
   - SQL script to migrate V1 → SCD2 tables
   - Preserve existing data with appropriate effective dates
   - Verify no data loss

## Files to Create/Modify

### New Files
- `customer-service/src/main/kotlin/com/example/usbilling/hr/domain/BitemporalEntities.kt`
- `customer-service/src/main/kotlin/com/example/usbilling/hr/repository/BitemporalRepositories.kt`
- `customer-service/src/main/kotlin/com/example/usbilling/hr/service/BitemporalCustomerService.kt`
- `customer-service/src/main/kotlin/com/example/usbilling/hr/http/BitemporalCustomerController.kt`
- `docs/adr/0001-bitemporal-customer-data.md`
- `scripts/migrate-v1-to-scd2.sql`

### Files to Modify
- `customer-service/src/main/kotlin/com/example/usbilling/hr/domain/CustomerEntities.kt` (apply Persistable to remaining entities)
- `e2e-tests/src/test/kotlin/com/example/usbilling/e2e/BillingWorkflowE2ETest.kt` (update to use v2 endpoints)

## Key SCD2 Concepts

### Bitemporal Tracking
- **Effective Time** (`effective_from`, `effective_to`): When the data was true in the real world
- **System Time** (`system_from`, `system_to`): When the system knew about the data

### Query Patterns
```sql
-- Current version (as of now)
SELECT * FROM customer_account_effective
WHERE account_id = ?
  AND system_from <= CURRENT_TIMESTAMP
  AND system_to > CURRENT_TIMESTAMP
  AND effective_from <= CURRENT_DATE
  AND effective_to > CURRENT_DATE;

-- Historical version (as of specific date)
SELECT * FROM customer_account_effective
WHERE account_id = ?
  AND system_from <= ?
  AND system_to > ?
  AND effective_from <= ?
  AND effective_to > ?;

-- All versions (complete history)
SELECT * FROM customer_account_effective
WHERE account_id = ?
ORDER BY effective_from DESC, system_from DESC;
```

### Update Pattern (Append-Only)
```sql
-- Step 1: Close current system version
UPDATE customer_account_effective
SET system_to = CURRENT_TIMESTAMP
WHERE account_id = ?
  AND system_to = '9999-12-31 23:59:59';

-- Step 2: Insert new version
INSERT INTO customer_account_effective (
    account_id, utility_id, customer_name, 
    effective_from, effective_to,
    system_from, system_to
) VALUES (
    ?, ?, ?,
    CURRENT_DATE, '9999-12-31',
    CURRENT_TIMESTAMP, '9999-12-31 23:59:59'
);
```

## Success Criteria

### Phase 1: E2E Tests (Current)
- ✅ 1/11 tests passing
- 🎯 Target: 11/11 tests passing with CRUD implementation

### Phase 2: SCD2 Implementation
- ✅ Bitemporal entities created
- ✅ Bitemporal repositories implemented
- ✅ Bitemporal service layer working
- ✅ V2 endpoints functional
- ✅ E2E tests migrated to v2

### Phase 3: Production Readiness
- ✅ Data migration from V1 → SCD2 complete
- ✅ All tests passing against SCD2 implementation
- ✅ Performance testing with realistic data volumes
- ✅ V1 endpoints deprecated

## References

- Database migrations: `customer-service/src/main/resources/db/migration/V014__*.sql` through V019
- Current CRUD implementation: `CustomerController.kt`, `CustomerService.kt`
- Billing domain models: `billing-domain/src/main/kotlin/com/example/usbilling/billing/model/`
- E2E test suite: `e2e-tests/src/test/kotlin/com/example/usbilling/e2e/BillingWorkflowE2ETest.kt`
