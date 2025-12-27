# Next Steps: Billing Platform Refactoring

## ✅ Phase 1 Complete (2025-12-27)

The initial fork is complete! You now have:
- Separate `us-billing-platform` repository at `/Users/andrewmathers/us-billing-platform`
- Full git history preserved (can trace back to payroll origins)
- Git remote configured as `payroll-upstream` (can pull future improvements)
- Clean branch: `initial-billing-fork`
- Documentation: `FORK_NOTES.md`, updated `README.md`

## 🚧 Phase 2: Core Renames (Recommended Next)

### Quick Commands for Package Rename

The fastest way to proceed is systematic find/replace:

```bash
cd /Users/andrewmathers/us-billing-platform

# 1. Rename package names in all Kotlin files
find . -type f -name "*.kt" -not -path "*/build/*" -exec sed -i '' 's/com\.example\.uspayroll/com.example.usbilling/g' {} +

# 2. Rename imports
find . -type f -name "*.kt" -not -path "*/build/*" -exec sed -i '' 's/import com\.example\.uspayroll/import com.example.usbilling/g' {} +

# 3. Rename identifiers in shared-kernel
find . -type f -name "*.kt" -not -path "*/build/*" -exec sed -i '' 's/EmployerId/UtilityId/g' {} +
find . -type f -name "*.kt" -not -path "*/build/*" -exec sed -i '' 's/EmployeeId/CustomerId/g' {} +
find . -type f -name "*.kt" -not -path "*/build/*" -exec sed -i '' 's/PayRunId/BillRunId/g' {} +
find . -type f -name "*.kt" -not -path "*/build/*" -exec sed -i '' 's/PaycheckId/BillId/g' {} +

# 4. Rename in build files
find . -type f -name "*.gradle.kts" -not -path "*/build/*" -exec sed -i '' 's/com\.example\.uspayroll/com.example.usbilling/g' {} +

# 5. Rename in YAML/properties files
find . -type f \( -name "*.yaml" -o -name "*.yml" -o -name "*.properties" \) -not -path "*/build/*" -exec sed -i '' 's/uspayroll/usbilling/g' {} +

# 6. Test the build
./gradlew clean build
```

### Module Directory Renames

After package renames work, rename the actual directories:

```bash
# Orchestrator
mv payroll-orchestrator-service billing-orchestrator-service
sed -i '' 's/"payroll-orchestrator-service"/"billing-orchestrator-service"/g' settings.gradle.kts

# Worker
mv payroll-worker-service billing-worker-service
sed -i '' 's/"payroll-worker-service"/"billing-worker-service"/g' settings.gradle.kts

# HR → Customer
mv hr-service customer-service
mv hr-api customer-api
mv hr-client customer-client
mv hr-domain customer-domain
sed -i '' 's/"hr-service"/"customer-service"/g' settings.gradle.kts
sed -i '' 's/"hr-api"/"customer-api"/g' settings.gradle.kts
sed -i '' 's/"hr-client"/"customer-client"/g' settings.gradle.kts
sed -i '' 's/"hr-domain"/"customer-domain"/g' settings.gradle.kts

# Tax → Rate
mv tax-service rate-service
mv tax-api rate-api
mv tax-config rate-config
mv tax-impl rate-impl
mv tax-content rate-content
mv tax-catalog-ports rate-catalog-ports
mv tax-domain rate-domain
# Update settings.gradle.kts for all tax → rate changes

# Labor → Regulatory
mv labor-service regulatory-service
mv labor-api regulatory-api
sed -i '' 's/"labor-service"/"regulatory-service"/g' settings.gradle.kts
sed -i '' 's/"labor-api"/"regulatory-api"/g' settings.gradle.kts

# Payroll domain modules
mv payroll-domain billing-domain
mv payroll-jackson billing-jackson
mv payroll-jackson-spring billing-jackson-spring
mv payroll-benchmarks billing-benchmarks
# Update settings.gradle.kts

# Time ingestion
mv time-ingestion-service meter-reading-service
sed -i '' 's/"time-ingestion-service"/"meter-reading-service"/g' settings.gradle.kts
```

## ⏳ Phase 3: Domain Replacement (After Renames)

Once renames are complete and building:

### Step 1: Gut the Payroll Domain
```bash
cd billing-domain/src/main/kotlin
# Remove all payroll calculation logic
# Keep only the structure: model.* and engine.* packages
```

### Step 2: Build New Billing Models
Create these in `billing-domain/src/main/kotlin/com/example/usbilling/billing/model/`:
- `MeterRead.kt` - Meter reading data
- `UsageType.kt` - Electric, Gas, Water enums
- `RateTariff.kt` - Rate structure definitions
- `ChargeLineItem.kt` - Individual charges
- `BillResult.kt` - Final bill output
- `AccountBalance.kt` - Customer balance tracking

### Step 3: Build Calculation Engine
Create in `billing-domain/src/main/kotlin/com/example/usbilling/billing/engine/`:
- `BillingEngine.kt` - Main orchestrator
- `UsageCalculator.kt` - Meter reads → consumption
- `RateApplier.kt` - Apply tariffs to usage
- `ChargeAggregator.kt` - Sum up all charges

## 📋 Verification Checklist

After each phase:
- [ ] `./gradlew clean build` succeeds
- [ ] All tests pass
- [ ] Git commit with clear message
- [ ] Update `FORK_NOTES.md` checklist

## 🆘 If You Get Stuck

### Common Issues

**Issue**: "Package does not exist" errors after rename
**Fix**: Make sure you renamed imports AND package declarations

**Issue**: Circular dependencies after module rename
**Fix**: Check `build.gradle.kts` dependencies - update module names

**Issue**: Tests failing after identifier rename
**Fix**: Check test fixtures and mock data - they use old identifiers

### Rollback Strategy

If something breaks badly:
```bash
# Rollback to last good commit
git reset --hard HEAD^

# Or create a new branch from specific commit
git checkout -b recover-from-step2 <commit-sha>
```

## 📞 Support Resources

- Architecture docs: `docs/architecture.md`
- Fork rationale: `FORK_NOTES.md`
- Original payroll repo: `/Users/andrewmathers/us-payroll-platform`

## Timeline Estimate

- **Phase 2 (Renames)**: 1 week (40 hours)
- **Phase 3 (Domain)**: 3 weeks (120 hours)
- **Phase 4 (Services)**: 2 weeks (80 hours)
- **Phase 5 (Features)**: 2.5 weeks (100 hours)

**Total**: ~2 months solo, or 1 month with pair programming

## Current Status: Ready for Phase 2! 🚀

You're set up for success. The infrastructure is solid, the patterns are proven, and you have ~70% code reuse. Start with the systematic package rename and go from there.

Good luck! 🎯
