# Schema Evolution Strategy

## Current State (Phase 4 Complete)

As of Phase 4, the us-billing-platform has completed surface-level renames:
- **Packages**: `com.example.uspayroll` → `com.example.usbilling`
- **Identifiers**: `EmployerId` → `UtilityId`, `EmployeeId` → `CustomerId`, etc.
- **Modules**: `hr-*` → `customer-*`, `tax-*` → `rate-*`, `labor-*` → `regulatory-*`
- **Services**: Spring application names updated to billing terminology
- **Flyway**: Migration directories renamed (preserving history)

However, **database schemas still use payroll domain naming**:
- Table names: `employee`, `pay_period`, `time_entry`, `paycheck`, `pay_run`, etc.
- Column names: `employer_id`, `employee_id`, `pay_run_id`, `paycheck_id`, etc.
- Migration files: V001__create_hr_schema.sql, V1__init_tax_rule.sql, etc.

## Why Tables Haven't Been Renamed

Table and column renames are **deferred to Phase 5+** because they require:

1. **Production coordination**: Cannot simply rename tables in a running production system
2. **Data migration complexity**: Requires dual-write periods or downtime
3. **Integration risk**: External systems may reference these tables
4. **Flyway limitations**: Cannot rename existing migrations without breaking history

## Technical Debt Accepted

We explicitly accept the following technical debt:

| Layer | Current State | Ideal State | Risk Level |
|-------|---------------|-------------|------------|
| Application code | ✅ `UtilityId`, `CustomerId` | ✅ Already done | ✅ None |
| API contracts | ✅ `/employers/{utilityId}/customers` | ✅ Already done | ✅ None |
| Database tables | ❌ `employee`, `pay_period` | `customer`, `bill_period` | ⚠️ Medium |
| Database columns | ❌ `employer_id`, `employee_id` | `utility_id`, `customer_id` | ⚠️ Medium |
| Flyway migrations | ❌ V001__create_hr_schema.sql | V001__create_customer_schema.sql | ⚠️ Low |

**Risk Assessment:**
- **Low risk**: Migration file names (V001__create_hr_schema.sql) are just file names - Flyway tracks by filename internally
- **Medium risk**: Table/column names create cognitive dissonance but don't break functionality
- **No functional impact**: The application layer already uses correct terminology

## Future Migration Paths

### Option 1: Blue-Green Database Migration (Recommended for Production)

**Best for:** Production systems with zero-downtime requirements

```sql
-- Phase A: Add new tables alongside old ones
CREATE TABLE customer AS SELECT * FROM employee;
CREATE TABLE bill_period AS SELECT * FROM pay_period;

-- Phase B: Dual-write period (application writes to both old and new tables)
-- Application code temporarily writes to both employee and customer tables

-- Phase C: Data verification and catchup
-- Verify new tables have all data from old tables

-- Phase D: Cutover (application reads from new tables)
-- Switch application to read from customer instead of employee

-- Phase E: Cleanup (drop old tables after verification period)
DROP TABLE employee;
DROP TABLE pay_period;
```

**Timeline**: 2-4 weeks with dual-write period

**Pros:**
- Zero downtime
- Rollback capability at each phase
- Safe for production

**Cons:**
- Complex coordination between app deployments and schema changes
- Requires significant testing
- Storage overhead during dual-write period

### Option 2: Maintenance Window Rename (Fast for Non-Production)

**Best for:** Development/staging environments or systems that can tolerate downtime

```sql
-- Single maintenance window, all changes atomic
ALTER TABLE employee RENAME TO customer;
ALTER TABLE pay_period RENAME TO bill_period;
ALTER TABLE paycheck RENAME TO bill;
ALTER TABLE pay_run RENAME TO bill_run;

-- Rename columns
ALTER TABLE customer RENAME COLUMN employer_id TO utility_id;
ALTER TABLE customer RENAME COLUMN employee_id TO customer_id;
-- ... (repeat for all tables and columns)
```

**Timeline**: 1-2 hours maintenance window

**Pros:**
- Simple and straightforward
- No dual-write complexity
- Fast execution

**Cons:**
- Requires downtime
- No rollback without backup
- Risk if something goes wrong

### Option 3: View-Based Compatibility Layer

**Best for:** Gradual migration with external integrations

```sql
-- Create new tables
CREATE TABLE customer ...;

-- Create views with old names for backwards compatibility
CREATE VIEW employee AS SELECT 
    utility_id as employer_id,
    customer_id as employee_id,
    ...
FROM customer;

-- Gradually migrate integrations to use new tables
-- Eventually drop views once all integrations updated
```

**Timeline**: Flexible, can span months

**Pros:**
- Backwards compatible
- Gradual migration
- Safe for systems with many integrations

**Cons:**
- Performance overhead of views
- Complexity in maintaining both schemas
- Potential for confusion

## Column Rename Strategy

Column renames are more complex than table renames because:
- Foreign keys must be updated
- Indexes must be rebuilt
- All queries must be updated

**Recommended approach:**
1. Add new columns alongside old ones
2. Populate new columns via triggers or application dual-write
3. Update application to use new columns
4. Drop old columns after verification period

Example for `employer_id` → `utility_id`:
```sql
-- Step 1: Add new column
ALTER TABLE customer ADD COLUMN utility_id VARCHAR(64);

-- Step 2: Populate from old column
UPDATE customer SET utility_id = employer_id;

-- Step 3: Add NOT NULL constraint after verification
ALTER TABLE customer ALTER COLUMN utility_id SET NOT NULL;

-- Step 4: Update foreign keys
-- (Requires coordination across all referencing tables)

-- Step 5: Drop old column (after application migration)
ALTER TABLE customer DROP COLUMN employer_id;
```

## Flyway Migration File Renames

**Current**: V001__create_hr_schema.sql  
**Desired**: V001__create_customer_schema.sql

**Recommendation**: **DO NOT rename existing migration files**

Flyway tracks migrations by filename. Renaming breaks the migration history. Instead:
- Keep existing migration files with their current names
- Accept that V001__create_hr_schema.sql creates a "customer" table (it's just a filename)
- Future migrations can use billing terminology: V014__add_meter_to_customer.sql

If you MUST have consistent filenames (e.g. for a fresh deployment):
1. Start a new Flyway baseline after full migration
2. Create new migration files with correct names
3. Mark old migrations as baseline in flyway_schema_history

## Coordination Checklist for Phase 5+

When ready to execute schema renames:

- [ ] **Inventory all tables**: List all tables that need renaming
- [ ] **Inventory all columns**: List all columns that need renaming across all tables
- [ ] **Dependency mapping**: Map all foreign key relationships
- [ ] **Integration audit**: Identify all systems that query these tables directly
- [ ] **Performance testing**: Test rename operations on staging with production-sized data
- [ ] **Rollback plan**: Document exact steps to rollback at each phase
- [ ] **Monitoring**: Set up alerts for query errors during migration
- [ ] **Documentation**: Update all API docs, runbooks, and architecture diagrams
- [ ] **Team communication**: Coordinate with all teams that might be affected

## Estimated Effort for Full Schema Migration

Based on current schema complexity:

- **Planning & design**: 1 week
- **Implementation**: 2-3 weeks
- **Testing**: 1-2 weeks
- **Execution & monitoring**: 1 week
- **Total**: 5-7 weeks (full-time equivalent)

## Current Recommendation

**For now (Phase 4)**: Accept the technical debt. The current state is functional and maintainable.

**For Phase 5+**: Plan schema migration only when:
1. Moving toward production deployment
2. External integrations require consistent naming
3. Team has bandwidth for 5-7 week migration effort

The application layer is correctly named, which is what matters most for maintainability.

---

*Last updated: 2025-12-27*  
*Current phase: Phase 4 (Service configuration complete)*
