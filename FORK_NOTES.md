# Fork Notes: US Billing Platform

## Provenance

**Forked from**: `us-payroll-platform`  
**Source commit**: `a07abf4` (Hardening config and orchestrator integration tests)  
**Fork date**: 2025-12-27  
**Git history**: Preserved via `git clone` (upstream remote: `payroll-upstream`)

## Rationale

The us-payroll-platform demonstrates production-grade architecture for:
- Multi-tenant (DB-per-entity) isolation
- Event-driven orchestration with outbox/inbox patterns
- Functional-core domain design
- Complex periodic calculations with audit trails
- Regulatory compliance and content versioning

These patterns are **70-80% transferable** to utility billing:
- Multi-tenant → Multi-utility
- Employee master → Customer Information System (CIS)
- Pay run cycles → Billing cycles
- Paycheck calculation → Bill calculation
- Tax rules catalog → Rate tariff catalog
- Statutory content pipelines → Regulatory rate updates

## Refactoring Strategy

### Phase 1: Initial Fork ✅ COMPLETE
- [x] Clone repository with full history
- [x] Rename git remote: `origin` → `payroll-upstream`
- [x] Create `initial-billing-fork` branch
- [x] Update root project name: `us-billing-platform`
- [x] Update README.md with fork status

### Phase 2: Core Renames 🚧 IN PROGRESS
- [ ] Rename all packages: `com.example.uspayroll` → `com.example.usbilling`
- [ ] Rename identifiers in shared-kernel:
  - `EmployerId` → `UtilityId`
  - `EmployeeId` → `CustomerId`
  - `PayRunId` → `BillRunId`
  - `PaycheckId` → `BillId`
- [ ] Rename module directories:
  - `payroll-orchestrator-service` → `billing-orchestrator-service`
  - `payroll-worker-service` → `billing-worker-service`
  - `hr-service` → `customer-service`
  - `tax-service` → `rate-service`
  - `labor-service` → `regulatory-service`

### Phase 3: Domain Replacement ⏳ PLANNED
- [ ] Delete `payroll-domain` module
- [ ] Create `billing-domain` module with:
  - Meter reading models (`MeterRead`, `UsageType`)
  - Usage calculation engine (`UsageCalculator`)
  - Rate engine (`RateTariff`, `TieredRate`, `TimeOfUseRate`)
  - Charge aggregation (`ChargeLineItem`, `BillResult`)
  - Payment allocation (`PaymentAllocation`)
- [ ] Update `customer-service` schema (employees → customers, meters)
- [ ] Build `rate-service` catalog (tax rules → rate tariffs)

### Phase 4: Service Adaptation ⏳ PLANNED
- [ ] Adapt orchestrator for billing cycles
- [ ] Update worker service for bill computation
- [ ] Build customer-facing APIs
- [ ] Add meter reading ingestion

### Phase 5: Billing-Specific Features ⏳ PLANNED
- [ ] Late fee calculation
- [ ] Budget billing
- [ ] Multi-commodity support (electric, gas, water)
- [ ] Disconnect/reconnect workflows
- [ ] PUC regulatory reporting

## Modules Reused As-Is (100%)

These are domain-agnostic and require **zero changes**:
- `persistence-core` - Flyway migrations, multi-tenant DB routing
- `messaging-core` - Event inbox/outbox patterns
- `web-core` - HTTP filters, idempotency, security, ProblemDetails
- `tenancy-core` - DB-per-tenant abstractions
- `edge-service` - API gateway (just needs config updates)
- All Kubernetes manifests in `deploy/k8s/`
- All CI/CD pipelines in `.github/workflows/`

## Estimated Effort

**Phase 2 (Renames)**: 40 hours (1 week)  
**Phase 3 (Domain)**: 120 hours (3 weeks)  
**Phase 4 (Services)**: 80 hours (2 weeks)  
**Phase 5 (Features)**: 100 hours (2.5 weeks)  

**Total**: ~340 hours (~2 months for 1 engineer, 1 month for 2 engineers)

## Value Proposition

**Greenfield utility billing platform**: 12-15 months, $1.2-1.8M  
**Forked from payroll platform**: 2-3 months, $300-400K  
**Savings**: $800K-1.4M (67-78% cost reduction)

## Links

- Upstream payroll repo: `/Users/andrewmathers/us-payroll-platform`
- Architecture analysis: See prior conversation
- Mapping document: See prior conversation (payroll → billing equivalents)
