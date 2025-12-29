# Customer Service Platform Implementation Plan
## Executive Summary
This plan delivers a complete customer service platform for the utility billing system, implementing customer-facing portals, proactive communications, case management, and operational tools. The implementation is divided into three phases over 12 months.
## Current State Analysis
### Strengths
* ✅ Excellent billing calculation engine (BillingEngine, PrepaidBillingEngine)
* ✅ Complete account and service management APIs
* ✅ Well-designed database schema for customer interactions (V018__create_interaction_and_case_management.sql)
* ✅ Payment processing infrastructure
* ✅ Multi-service support (electric, gas, water, wastewater, stormwater)
* ✅ Service connection/disconnection workflows with validation
### Critical Gaps
* ❌ No customer-facing portal or REST APIs
* ❌ Notification service is placeholder (empty module)
* ❌ Case management has schema but no workflow implementation
* ❌ No outage management system
* ❌ No payment plans or auto-pay
* ❌ No bill PDF generation or presentment
* ❌ No dispute management workflow
* ❌ No CSR dashboard or tools
## Architecture Overview
### New Services to Implement
1. **customer-portal-service** (port 8090) - Customer self-service REST API
2. **notification-service** (port 8091) - Multi-channel notifications (email, SMS, push)
3. **case-management-service** (port 8092) - Case workflow and assignment
4. **outage-service** (port 8093) - Outage reporting and tracking
5. **document-service** (port 8094) - Bill PDF generation and storage
6. **csr-portal-service** (port 8095) - CSR tools and dashboards
### Service Dependencies
```warp-runnable-command
customer-portal-service
  ├─→ customer-service (account data)
  ├─→ billing-orchestrator-service (bills)
  ├─→ payments-service (payments)
  ├─→ document-service (bill PDFs)
  ├─→ notification-service (preferences)
  └─→ case-management-service (submit cases)
notification-service
  ├─→ customer-service (customer contact info)
  └─→ external providers (SendGrid, Twilio)
case-management-service
  ├─→ customer-service (customer context)
  ├─→ notification-service (case updates)
  └─→ outage-service (outage-related cases)
csr-portal-service
  ├─→ customer-service (360° view)
  ├─→ billing-orchestrator-service (bill history)
  ├─→ case-management-service (case management)
  ├─→ payments-service (payment history)
  └─→ outage-service (outage status)
```

***
# Phase 1: Customer Service Fundamentals (Months 1-3)
**Goal:** Enable basic customer self-service and reduce CSR call volume by 30-40%
## Sprint 1 (Weeks 1-2): Foundation & Infrastructure
### 1.1 Create Customer Portal Service Module
**Location:** `customer-portal-service/`
**Tasks:**
* Create Gradle module with Spring Boot setup
* Configure port 8090 in application.yml
* Add dependencies: web-core, customer-api, billing-domain
* Create CustomerPortalApplication.kt main class
* Add Dockerfile with non-root user (uid=10001)
* Add health check endpoints
**Files to Create:**
* `customer-portal-service/build.gradle.kts`
* `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/CustomerPortalApplication.kt`
* `customer-portal-service/src/main/resources/application.yml`
* `customer-portal-service/Dockerfile`
### 1.2 Create Notification Service Module
**Location:** `notification-service/`
**Tasks:**
* Create Gradle module (replace empty placeholder)
* Configure port 8091
* Add Spring Boot, messaging-core dependencies
* Create NotificationApplication.kt
* Add provider abstraction (EmailProvider, SmsProvider)
* Implement SendGrid email provider
* Implement Twilio SMS provider (dev mode: log only)
**Files to Create:**
* `notification-service/build.gradle.kts`
* `notification-service/src/main/kotlin/com/example/usbilling/notification/NotificationApplication.kt`
* `notification-service/src/main/kotlin/com/example/usbilling/notification/provider/EmailProvider.kt`
* `notification-service/src/main/kotlin/com/example/usbilling/notification/provider/SmsProvider.kt`
* `notification-service/src/main/kotlin/com/example/usbilling/notification/provider/SendGridEmailProvider.kt`
* `notification-service/src/main/kotlin/com/example/usbilling/notification/provider/TwilioSmsProvider.kt`
### 1.3 Create Document Service Module
**Location:** `document-service/`
**Tasks:**
* Create Gradle module for document generation
* Configure port 8094
* Add iText/Apache PDFBox dependency for PDF generation
* Create DocumentApplication.kt
* Implement bill PDF template
* Add S3/local storage abstraction
**Files to Create:**
* `document-service/build.gradle.kts`
* `document-service/src/main/kotlin/com/example/usbilling/document/DocumentApplication.kt`
* `document-service/src/main/kotlin/com/example/usbilling/document/generator/BillPdfGenerator.kt`
* `document-service/src/main/kotlin/com/example/usbilling/document/storage/DocumentStorage.kt`
* `document-service/src/main/resources/templates/bill-template.html`
### 1.4 Update settings.gradle.kts
**Tasks:**
* Add customer-portal-service to included projects
* Add notification-service (replace placeholder)
* Add document-service to included projects
* Update module dependency graph in documentation
***
## Sprint 2 (Weeks 3-4): Customer Portal - View Bills
### 2.1 Customer Authentication & Authorization
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/security/`
**Tasks:**
* Implement CustomerAuthenticationFilter (JWT-based)
* Create CustomerPrincipal (customerId, accountIds)
* Add tenant scoping (utilityId from JWT)
* Implement rate limiting (Spring Bucket4j)
* Add CORS configuration for web/mobile clients
**Files to Create:**
* `CustomerAuthenticationFilter.kt`
* `CustomerPrincipal.kt`
* `CustomerSecurityConfig.kt`
* `RateLimitingFilter.kt`
### 2.2 Bill Retrieval API
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/http/`
**Tasks:**
* Create BillController with customer-scoped endpoints
* GET /api/customers/me/bills (list with pagination)
* GET /api/customers/me/bills/{billId} (single bill detail)
* GET /api/customers/me/bills/{billId}/pdf (bill PDF)
* GET /api/customers/me/bills/summary (current balance, due date)
* Add tenant isolation validation
* Integrate with billing-orchestrator-service via WebClient
* Integrate with document-service for PDF generation
**Files to Create:**
* `BillController.kt`
* `BillSummaryResponse.kt`
* `BillDetailResponse.kt`
* `BillingOrchestratorClient.kt`
**Example Endpoint:**
```kotlin
@GetMapping("/api/customers/me/bills")
fun listBills(
    @AuthenticationPrincipal principal: CustomerPrincipal,
    @RequestParam(defaultValue = "0") page: Int,
    @RequestParam(defaultValue = "20") size: Int,
    @RequestParam(required = false) status: String?
): ResponseEntity<PagedBillsResponse> {
    val bills = billingClient.listCustomerBills(
        utilityId = principal.utilityId,
        customerId = principal.customerId,
        status = status,
        page = page,
        size = size
    )
    return ResponseEntity.ok(bills)
}
```
### 2.3 Bill PDF Generation
**Location:** `document-service/src/main/kotlin/com/example/usbilling/document/generator/`
**Tasks:**
* Implement BillPdfGenerator using iText
* Create bill template with utility branding
* Include bill summary, line items, usage chart
* Add payment instructions and due date
* Include QR code for mobile payment
* Cache generated PDFs (S3 or local)
**Files to Create:**
* `BillPdfGenerator.kt`
* `PdfTemplateEngine.kt`
* `BillPdfRequest.kt`
### 2.4 Account Information API
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/http/`
**Tasks:**
* Create AccountController
* GET /api/customers/me/accounts (list customer accounts)
* GET /api/customers/me/accounts/{accountId} (account detail)
* GET /api/customers/me/accounts/{accountId}/services (active services)
* GET /api/customers/me/profile (customer profile)
* PUT /api/customers/me/profile (update contact info)
**Files to Create:**
* `AccountController.kt`
* `ProfileController.kt`
* `AccountSummaryResponse.kt`
***
## Sprint 3 (Weeks 5-6): Payment Processing
### 3.1 Payment API
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/http/`
**Tasks:**
* Create PaymentController
* POST /api/customers/me/payments (submit one-time payment)
* GET /api/customers/me/payments (payment history)
* GET /api/customers/me/payments/{paymentId} (payment detail)
* Integrate with payments-service
* Add payment method tokenization (Stripe/Square)
* Implement idempotency (Idempotency-Key header)
**Files to Create:**
* `PaymentController.kt`
* `SubmitPaymentRequest.kt`
* `PaymentResponse.kt`
* `PaymentClient.kt`
**Example Endpoint:**
```kotlin
@PostMapping("/api/customers/me/payments")
fun submitPayment(
    @AuthenticationPrincipal principal: CustomerPrincipal,
    @RequestHeader("Idempotency-Key") idempotencyKey: String,
    @RequestBody request: SubmitPaymentRequest
): ResponseEntity<PaymentResponse> {
    // Validate account ownership
    validateAccountOwnership(principal, request.accountId)
    
    val payment = paymentClient.submitPayment(
        utilityId = principal.utilityId,
        customerId = principal.customerId,
        request = request,
        idempotencyKey = idempotencyKey
    )
    
    return ResponseEntity.status(HttpStatus.CREATED).body(payment)
}
```
### 3.2 Prepaid Account Recharge API
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/http/`
**Tasks:**
* Create PrepaidController
* POST /api/customers/me/prepaid-accounts/{accountId}/recharge (add funds)
* GET /api/customers/me/prepaid-accounts/{accountId}/balance (current balance)
* GET /api/customers/me/prepaid-accounts/{accountId}/usage-history (usage deductions)
* PUT /api/customers/me/prepaid-accounts/{accountId}/auto-recharge (enable/disable)
* Integrate with PrepaidBillingEngine
**Files to Create:**
* `PrepaidController.kt`
* `RechargeRequest.kt`
* `PrepaidBalanceResponse.kt`
### 3.3 Payment Method Management
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/http/`
**Tasks:**
* Create PaymentMethodController
* POST /api/customers/me/payment-methods (add payment method)
* GET /api/customers/me/payment-methods (list saved methods)
* DELETE /api/customers/me/payment-methods/{methodId} (remove method)
* PUT /api/customers/me/payment-methods/{methodId}/default (set default)
* Integrate with payment tokenization provider (Stripe)
**Files to Create:**
* `PaymentMethodController.kt`
* `PaymentMethodRequest.kt`
* `PaymentMethodResponse.kt`
**Database Migration:**
* Create `customer-service/src/main/resources/db/migration/customer/V020__create_payment_method_tables.sql`
```SQL
CREATE TABLE payment_method (
    method_id VARCHAR(255) PRIMARY KEY,
    customer_id VARCHAR(255) NOT NULL,
    utility_id VARCHAR(255) NOT NULL,
    method_type VARCHAR(50) NOT NULL, -- CREDIT_CARD, BANK_ACCOUNT
    provider_token VARCHAR(500) NOT NULL, -- Tokenized, never raw card/account
    last_four VARCHAR(4),
    expiry_month INT,
    expiry_year INT,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```
***
## Sprint 4 (Weeks 7-8): Notification Infrastructure
### 4.1 Notification Service Core
**Location:** `notification-service/src/main/kotlin/com/example/usbilling/notification/service/`
**Tasks:**
* Create NotificationService with send() method
* Implement notification queuing (RabbitMQ)
* Add retry logic with exponential backoff
* Create notification templates (bill ready, payment due, etc.)
* Add template rendering engine (Mustache/Freemarker)
* Log all notifications to communication_log table
**Files to Create:**
* `NotificationService.kt`
* `NotificationQueueListener.kt`
* `NotificationTemplateEngine.kt`
* `NotificationRepository.kt`
### 4.2 Notification Templates
**Location:** `notification-service/src/main/resources/templates/`
**Tasks:**
* Create email templates:
    * bill-ready.html
    * payment-due.html
    * payment-received.html
    * payment-failed.html
    * prepaid-low-balance.html
    * prepaid-critical-balance.html
* Create SMS templates (plain text, 160 char limit)
* Add template versioning
**Files to Create:**
* `templates/email/bill-ready.html`
* `templates/email/payment-due.html`
* `templates/sms/bill-ready.txt`
* `templates/sms/payment-due.txt`
### 4.3 Notification Preferences API
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/http/`
**Tasks:**
* Create NotificationPreferenceController
* GET /api/customers/me/notification-preferences (current preferences)
* PUT /api/customers/me/notification-preferences (update preferences)
* Use existing notification_preference_effective table (bitemporal)
* Implement effective dating logic
**Files to Create:**
* `NotificationPreferenceController.kt`
* `NotificationPreferenceRequest.kt`
* `NotificationPreferenceResponse.kt`
### 4.4 Billing Event Listeners
**Location:** `notification-service/src/main/kotlin/com/example/usbilling/notification/listener/`
**Tasks:**
* Create BillingEventListener (consume from Kafka)
* Listen for BILL_FINALIZED events → send bill-ready notification
* Listen for PAYMENT_RECEIVED events → send payment-received notification
* Listen for PAYMENT_DUE events (scheduled job) → send payment-due notification
* Check customer notification preferences before sending
* Respect opt-out preferences
**Files to Create:**
* `BillingEventListener.kt`
* `PaymentEventListener.kt`
* `ScheduledNotificationJob.kt`
***
## Sprint 5 (Weeks 9-10): Outage Management
### 5.1 Create Outage Service Module
**Location:** `outage-service/`
**Tasks:**
* Create Gradle module
* Configure port 8093
* Add Spring Boot, persistence-core dependencies
* Create OutageApplication.kt
* Create database schema for outages
**Files to Create:**
* `outage-service/build.gradle.kts`
* `outage-service/src/main/kotlin/com/example/usbilling/outage/OutageApplication.kt`
* `outage-service/src/main/resources/db/migration/outage/V001__create_outage_tables.sql`
**Database Schema:**
```SQL
CREATE TABLE outage (
    outage_id VARCHAR(255) PRIMARY KEY,
    utility_id VARCHAR(255) NOT NULL,
    outage_type VARCHAR(50) NOT NULL, -- PLANNED, UNPLANNED
    cause VARCHAR(100), -- STORM, EQUIPMENT_FAILURE, VEHICLE_ACCIDENT, etc.
    status VARCHAR(50) NOT NULL, -- REPORTED, INVESTIGATING, CREW_DISPATCHED, RESTORING, RESTORED
    affected_service_type VARCHAR(50) NOT NULL, -- ELECTRIC, GAS, WATER
    affected_area_description TEXT,
    affected_customer_count INT,
    estimated_restoration_time TIMESTAMP,
    actual_restoration_time TIMESTAMP,
    reported_at TIMESTAMP NOT NULL,
    restored_at TIMESTAMP,
    created_by VARCHAR(255),
    notes TEXT
);
CREATE TABLE outage_affected_customer (
    id VARCHAR(255) PRIMARY KEY,
    outage_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    notified_at TIMESTAMP,
    FOREIGN KEY (outage_id) REFERENCES outage(outage_id)
);
CREATE TABLE outage_status_history (
    history_id VARCHAR(255) PRIMARY KEY,
    outage_id VARCHAR(255) NOT NULL,
    from_status VARCHAR(50),
    to_status VARCHAR(50) NOT NULL,
    changed_at TIMESTAMP NOT NULL,
    changed_by VARCHAR(255),
    notes TEXT,
    FOREIGN KEY (outage_id) REFERENCES outage(outage_id)
);
```
### 5.2 Outage Management API (Internal - CSR)
**Location:** `outage-service/src/main/kotlin/com/example/usbilling/outage/http/`
**Tasks:**
* Create OutageController
* POST /utilities/{utilityId}/outages (create outage)
* GET /utilities/{utilityId}/outages (list outages)
* GET /utilities/{utilityId}/outages/{outageId} (outage detail)
* PUT /utilities/{utilityId}/outages/{outageId}/status (update status)
* POST /utilities/{utilityId}/outages/{outageId}/affected-customers (add affected customers)
* PUT /utilities/{utilityId}/outages/{outageId}/estimated-restoration (update ETR)
**Files to Create:**
* `OutageController.kt`
* `CreateOutageRequest.kt`
* `OutageResponse.kt`
* `OutageService.kt`
### 5.3 Customer Outage API
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/http/`
**Tasks:**
* Create OutageController (customer-facing)
* POST /api/customers/me/outage-reports (report outage)
* GET /api/customers/me/outages (active outages affecting customer)
* GET /api/customers/me/outages/{outageId} (outage status)
* Integrate with outage-service via WebClient
**Files to Create:**
* `OutageController.kt`
* `OutageReportRequest.kt`
* `OutageStatusResponse.kt`
* `OutageClient.kt`
### 5.4 Outage Notifications
**Location:** `notification-service/src/main/kotlin/com/example/usbilling/notification/listener/`
**Tasks:**
* Create OutageEventListener
* Listen for OUTAGE_CREATED events → notify affected customers
* Listen for OUTAGE_STATUS_CHANGED events → send status updates
* Listen for OUTAGE_RESTORED events → send restoration confirmation
* Create outage notification templates
**Files to Create:**
* `OutageEventListener.kt`
* `templates/email/outage-reported.html`
* `templates/email/outage-restored.html`
* `templates/sms/outage-reported.txt`
***
## Sprint 6 (Weeks 11-12): Case Management Workflow
### 6.1 Create Case Management Service Module
**Location:** `case-management-service/`
**Tasks:**
* Create Gradle module
* Configure port 8092
* Add Spring Boot, messaging-core dependencies
* Create CaseManagementApplication.kt
* Use existing case tables from customer-service (or migrate to separate DB)
**Files to Create:**
* `case-management-service/build.gradle.kts`
* `case-management-service/src/main/kotlin/com/example/usbilling/casemanagement/CaseManagementApplication.kt`
* `case-management-service/src/main/resources/application.yml`
### 6.2 Case Management API
**Location:** `case-management-service/src/main/kotlin/com/example/usbilling/casemanagement/http/`
**Tasks:**
* Create CaseController
* POST /utilities/{utilityId}/cases (create case)
* GET /utilities/{utilityId}/cases (list cases with filters)
* GET /utilities/{utilityId}/cases/{caseId} (case detail)
* PUT /utilities/{utilityId}/cases/{caseId}/status (update status)
* POST /utilities/{utilityId}/cases/{caseId}/notes (add note)
* PUT /utilities/{utilityId}/cases/{caseId}/assign (assign to CSR/team)
* Use existing case_record, case_note, case_status_history tables
**Files to Create:**
* `CaseController.kt`
* `CreateCaseRequest.kt`
* `CaseResponse.kt`
* `CaseService.kt`
* `CaseRepository.kt`
**Example Endpoint:**
```kotlin
@PostMapping("/utilities/{utilityId}/cases")
fun createCase(
    @PathVariable utilityId: String,
    @RequestBody request: CreateCaseRequest
): ResponseEntity<CaseResponse> {
    val caseId = UUID.randomUUID().toString()
    val caseNumber = generateCaseNumber(utilityId) // e.g., "CASE-2025-001234"
    
    val caseRecord = CaseRecord(
        caseId = caseId,
        caseNumber = caseNumber,
        utilityId = utilityId,
        accountId = request.accountId,
        customerId = request.customerId,
        caseType = request.caseType, // SERVICE_REQUEST, COMPLAINT, DISPUTE
        caseCategory = request.caseCategory, // BILLING, PAYMENT, METER, etc.
        status = "OPEN",
        priority = request.priority ?: "MEDIUM",
        title = request.title,
        description = request.description,
        openedBy = request.openedBy
    )
    
    val saved = caseRepository.save(caseRecord)
    return ResponseEntity.status(HttpStatus.CREATED).body(saved.toResponse())
}
```
### 6.3 Customer Case API
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/http/`
**Tasks:**
* Create CaseController (customer-facing)
* POST /api/customers/me/cases (submit case/inquiry)
* GET /api/customers/me/cases (list customer's cases)
* GET /api/customers/me/cases/{caseId} (case detail and notes)
* POST /api/customers/me/cases/{caseId}/notes (add customer note)
* Integrate with case-management-service
**Files to Create:**
* `CaseController.kt`
* `SubmitCaseRequest.kt`
* `CaseDetailResponse.kt`
* `CaseManagementClient.kt`
### 6.4 Case Assignment & Routing
**Location:** `case-management-service/src/main/kotlin/com/example/usbilling/casemanagement/service/`
**Tasks:**
* Implement CaseRoutingService
* Auto-assign cases based on category (billing → billing team)
* Round-robin assignment within team
* Escalation rules (high priority → supervisor)
* Record assignment history in case_assignment_history table
**Files to Create:**
* `CaseRoutingService.kt`
* `CaseAssignmentRepository.kt`
* `TeamConfiguration.kt`
### 6.5 Case Notifications
**Location:** `notification-service/src/main/kotlin/com/example/usbilling/notification/listener/`
**Tasks:**
* Create CaseEventListener
* Listen for CASE_CREATED → notify customer (case number, estimated resolution)
* Listen for CASE_STATUS_CHANGED → notify customer
* Listen for CASE_RESOLVED → notify customer (resolution notes)
* Listen for CASE_NOTE_ADDED → notify customer if note is customer-visible
**Files to Create:**
* `CaseEventListener.kt`
* `templates/email/case-created.html`
* `templates/email/case-resolved.html`
***
## Phase 1 Deliverables Summary
**Completed Capabilities:**
* ✅ Customer portal (view bills, make payments, view account)
* ✅ Bill PDF generation and delivery
* ✅ Payment processing (one-time payments)
* ✅ Prepaid account recharge
* ✅ Payment method management
* ✅ Notification infrastructure (email, SMS)
* ✅ Proactive notifications (bill ready, payment due, payment received)
* ✅ Outage reporting and status tracking
* ✅ Case management (submit cases, track status)
* ✅ Notification preferences
**Expected Impact:**
* 30-40% reduction in CSR call volume
* 24/7 customer self-service
* Improved payment timeliness
* Better outage communication
***
# Phase 2: Proactive Engagement & Collections (Months 4-6)
**Goal:** Reduce payment delinquency and improve customer satisfaction
## Sprint 7 (Weeks 13-14): Payment Plans
### 7.1 Payment Plan Schema
**Location:** `customer-service/src/main/resources/db/migration/customer/V021__create_payment_plan_tables.sql`
**Tasks:**
* Create payment_plan table
* Create payment_plan_installment table
* Create payment_plan_payment table (links payments to installments)
**Database Schema:**
```SQL
CREATE TABLE payment_plan (
    plan_id VARCHAR(255) PRIMARY KEY,
    utility_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    plan_type VARCHAR(50) NOT NULL, -- STANDARD, HARDSHIP, BUDGET_BILLING
    status VARCHAR(50) NOT NULL, -- ACTIVE, COMPLETED, BROKEN, CANCELLED
    total_amount_cents BIGINT NOT NULL,
    down_payment_cents BIGINT NOT NULL DEFAULT 0,
    remaining_balance_cents BIGINT NOT NULL,
    installment_amount_cents BIGINT NOT NULL,
    installment_count INT NOT NULL,
    installments_paid INT NOT NULL DEFAULT 0,
    payment_frequency VARCHAR(50) NOT NULL, -- WEEKLY, BIWEEKLY, MONTHLY
    start_date DATE NOT NULL,
    first_payment_date DATE NOT NULL,
    final_payment_date DATE NOT NULL,
    missed_payments INT NOT NULL DEFAULT 0,
    max_missed_payments INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255) NOT NULL,
    cancelled_at TIMESTAMP,
    cancelled_reason VARCHAR(500)
);
CREATE TABLE payment_plan_installment (
    installment_id VARCHAR(255) PRIMARY KEY,
    plan_id VARCHAR(255) NOT NULL,
    installment_number INT NOT NULL,
    due_date DATE NOT NULL,
    amount_cents BIGINT NOT NULL,
    paid_amount_cents BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(50) NOT NULL, -- PENDING, PAID, PARTIAL, MISSED, WAIVED
    paid_at TIMESTAMP,
    FOREIGN KEY (plan_id) REFERENCES payment_plan(plan_id),
    UNIQUE (plan_id, installment_number)
);
CREATE TABLE payment_plan_payment (
    id VARCHAR(255) PRIMARY KEY,
    plan_id VARCHAR(255) NOT NULL,
    installment_id VARCHAR(255) NOT NULL,
    payment_id VARCHAR(255) NOT NULL,
    amount_cents BIGINT NOT NULL,
    applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (plan_id) REFERENCES payment_plan(plan_id),
    FOREIGN KEY (installment_id) REFERENCES payment_plan_installment(installment_id)
);
```
### 7.2 Payment Plan Service
**Location:** `customer-service/src/main/kotlin/com/example/usbilling/hr/service/`
**Tasks:**
* Create PaymentPlanService
* Implement plan creation with installment generation
* Implement payment application logic
* Implement plan monitoring (detect missed payments)
* Implement plan completion/broken status updates
**Files to Create:**
* `PaymentPlanService.kt`
* `PaymentPlanRepository.kt`
* `PaymentPlanInstallmentRepository.kt`
### 7.3 Payment Plan API
**Location:** `customer-service/src/main/kotlin/com/example/usbilling/hr/http/`
**Tasks:**
* Create PaymentPlanController
* POST /utilities/{utilityId}/customers/{customerId}/payment-plans (create plan)
* GET /utilities/{utilityId}/customers/{customerId}/payment-plans (list plans)
* GET /utilities/{utilityId}/payment-plans/{planId} (plan detail)
* POST /utilities/{utilityId}/payment-plans/{planId}/payments (apply payment)
* PUT /utilities/{utilityId}/payment-plans/{planId}/cancel (cancel plan)
**Files to Create:**
* `PaymentPlanController.kt`
* `CreatePaymentPlanRequest.kt`
* `PaymentPlanResponse.kt`
### 7.4 Customer Payment Plan API
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/http/`
**Tasks:**
* Create PaymentPlanController (customer-facing)
* POST /api/customers/me/payment-plans/eligibility (check if eligible)
* POST /api/customers/me/payment-plans (request payment plan)
* GET /api/customers/me/payment-plans (list active plans)
* GET /api/customers/me/payment-plans/{planId} (plan detail with schedule)
* Integrate with customer-service payment plan APIs
**Files to Create:**
* `PaymentPlanController.kt`
* `PaymentPlanEligibilityRequest.kt`
* `PaymentPlanEligibilityResponse.kt`
***
## Sprint 8 (Weeks 15-16): Auto-Pay
### 8.1 Auto-Pay Schema
**Location:** `customer-service/src/main/resources/db/migration/customer/V022__create_autopay_tables.sql`
**Tasks:**
* Create autopay_enrollment table
* Create autopay_execution table (audit trail)
**Database Schema:**
```SQL
CREATE TABLE autopay_enrollment (
    enrollment_id VARCHAR(255) PRIMARY KEY,
    utility_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    payment_method_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL, -- ACTIVE, SUSPENDED, CANCELLED
    payment_timing VARCHAR(50) NOT NULL, -- ON_DUE_DATE, FIXED_DAY
    fixed_day_of_month INT, -- 1-28 if FIXED_DAY
    amount_type VARCHAR(50) NOT NULL, -- FULL_BALANCE, MINIMUM_DUE, FIXED_AMOUNT
    fixed_amount_cents BIGINT, -- if FIXED_AMOUNT
    enrolled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_at TIMESTAMP,
    cancelled_reason VARCHAR(500),
    FOREIGN KEY (payment_method_id) REFERENCES payment_method(method_id)
);
CREATE TABLE autopay_execution (
    execution_id VARCHAR(255) PRIMARY KEY,
    enrollment_id VARCHAR(255) NOT NULL,
    bill_id VARCHAR(255),
    scheduled_date DATE NOT NULL,
    executed_at TIMESTAMP,
    amount_cents BIGINT NOT NULL,
    status VARCHAR(50) NOT NULL, -- SCHEDULED, SUCCESS, FAILED, SKIPPED
    failure_reason VARCHAR(500),
    payment_id VARCHAR(255), -- if successful
    retry_count INT NOT NULL DEFAULT 0,
    FOREIGN KEY (enrollment_id) REFERENCES autopay_enrollment(enrollment_id)
);
```
### 8.2 Auto-Pay Service
**Location:** `customer-service/src/main/kotlin/com/example/usbilling/hr/service/`
**Tasks:**
* Create AutoPayService
* Implement enrollment logic
* Implement scheduled execution job (daily cron)
* Implement retry logic for failed payments
* Suspend enrollment after 3 consecutive failures
**Files to Create:**
* `AutoPayService.kt`
* `AutoPayEnrollmentRepository.kt`
* `AutoPayExecutionRepository.kt`
* `AutoPayScheduledJob.kt`
### 8.3 Auto-Pay API
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/http/`
**Tasks:**
* Create AutoPayController
* POST /api/customers/me/autopay (enroll in auto-pay)
* GET /api/customers/me/autopay (enrollment status)
* PUT /api/customers/me/autopay (update settings)
* DELETE /api/customers/me/autopay (cancel auto-pay)
* GET /api/customers/me/autopay/history (execution history)
**Files to Create:**
* `AutoPayController.kt`
* `AutoPayEnrollmentRequest.kt`
* `AutoPayEnrollmentResponse.kt`
### 8.4 Auto-Pay Notifications
**Location:** `notification-service/src/main/kotlin/com/example/usbilling/notification/listener/`
**Tasks:**
* Listen for AUTOPAY_SUCCESS → send confirmation
* Listen for AUTOPAY_FAILED → send failure alert (with action)
* Listen for AUTOPAY_SUSPENDED → send suspension notice
* Create notification templates
**Files to Create:**
* `AutoPayEventListener.kt`
* `templates/email/autopay-success.html`
* `templates/email/autopay-failed.html`
***
## Sprint 9 (Weeks 17-18): Dispute Management
### 9.1 Dispute Schema
**Location:** `customer-service/src/main/resources/db/migration/customer/V023__create_dispute_tables.sql`
**Tasks:**
* Create billing_dispute table
* Create dispute_investigation table
* Create dispute_resolution table
**Database Schema:**
```SQL
CREATE TABLE billing_dispute (
    dispute_id VARCHAR(255) PRIMARY KEY,
    utility_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    bill_id VARCHAR(255),
    dispute_type VARCHAR(50) NOT NULL, -- HIGH_BILL, ESTIMATED_BILL, METER_ACCURACY, SERVICE_QUALITY, CHARGE_ERROR
    dispute_reason TEXT NOT NULL,
    disputed_amount_cents BIGINT,
    status VARCHAR(50) NOT NULL, -- SUBMITTED, INVESTIGATING, RESOLVED, CLOSED, ESCALATED
    priority VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP,
    case_id VARCHAR(255), -- Link to case_record if escalated
    created_by VARCHAR(255)
);
CREATE TABLE dispute_investigation (
    investigation_id VARCHAR(255) PRIMARY KEY,
    dispute_id VARCHAR(255) NOT NULL,
    assigned_to VARCHAR(255) NOT NULL,
    investigation_notes TEXT,
    meter_test_requested BOOLEAN NOT NULL DEFAULT FALSE,
    meter_test_result VARCHAR(100),
    field_visit_required BOOLEAN NOT NULL DEFAULT FALSE,
    field_visit_completed_at TIMESTAMP,
    findings TEXT,
    recommendation VARCHAR(100), -- APPROVE_ADJUSTMENT, DENY, PARTIAL_ADJUSTMENT, ESCALATE
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (dispute_id) REFERENCES billing_dispute(dispute_id)
);
CREATE TABLE dispute_resolution (
    resolution_id VARCHAR(255) PRIMARY KEY,
    dispute_id VARCHAR(255) NOT NULL,
    resolution_type VARCHAR(50) NOT NULL, -- ADJUSTMENT_APPROVED, DENIED, PARTIAL_ADJUSTMENT, ESCALATED_TO_COMMISSION
    adjustment_amount_cents BIGINT,
    adjustment_applied BOOLEAN NOT NULL DEFAULT FALSE,
    resolution_notes TEXT NOT NULL,
    customer_notified_at TIMESTAMP,
    resolved_by VARCHAR(255) NOT NULL,
    resolved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (dispute_id) REFERENCES billing_dispute(dispute_id)
);
```
### 9.2 Dispute Management Service
**Location:** `case-management-service/src/main/kotlin/com/example/usbilling/casemanagement/dispute/`
**Tasks:**
* Create DisputeService
* Implement dispute submission workflow
* Implement investigation workflow
* Implement resolution workflow with billing adjustments
* Integrate with billing-orchestrator for credit application
**Files to Create:**
* `DisputeService.kt`
* `DisputeRepository.kt`
* `DisputeInvestigationRepository.kt`
* `DisputeResolutionRepository.kt`
### 9.3 Dispute API
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/http/`
**Tasks:**
* Create DisputeController (customer-facing)
* POST /api/customers/me/disputes (submit dispute)
* GET /api/customers/me/disputes (list disputes)
* GET /api/customers/me/disputes/{disputeId} (dispute status and resolution)
* Integrate with case-management-service
**Files to Create:**
* `DisputeController.kt`
* `SubmitDisputeRequest.kt`
* `DisputeStatusResponse.kt`
### 9.4 Dispute Notifications
**Location:** `notification-service/`
**Tasks:**
* Listen for DISPUTE_SUBMITTED → send acknowledgment
* Listen for DISPUTE_INVESTIGATING → send investigation notice
* Listen for DISPUTE_RESOLVED → send resolution (with adjustment details)
* Create templates
***
## Sprint 10 (Weeks 19-20): Usage Insights & Alerts
### 10.1 Usage Analytics Service
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/service/`
**Tasks:**
* Create UsageAnalyticsService
* Implement usage comparison logic (vs. last month, last year)
* Implement projected bill calculation (mid-cycle)
* Implement usage trend analysis
* Query meter_read table for historical data
**Files to Create:**
* `UsageAnalyticsService.kt`
* `UsageComparisonCalculator.kt`
* `ProjectedBillCalculator.kt`
### 10.2 Usage Insights API
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/http/`
**Tasks:**
* Create UsageController
* GET /api/customers/me/accounts/{accountId}/usage/current (current billing period)
* GET /api/customers/me/accounts/{accountId}/usage/history (12-month history)
* GET /api/customers/me/accounts/{accountId}/usage/comparison (vs. last period)
* GET /api/customers/me/accounts/{accountId}/usage/projected-bill (mid-cycle estimate)
* GET /api/customers/me/accounts/{accountId}/usage/chart (usage chart data)
**Files to Create:**
* `UsageController.kt`
* `UsageHistoryResponse.kt`
* `UsageComparisonResponse.kt`
* `ProjectedBillResponse.kt`
### 10.3 Usage Alert Configuration
**Location:** `customer-service/src/main/resources/db/migration/customer/V024__create_usage_alert_tables.sql`
**Tasks:**
* Create usage_alert_config table
* Create usage_alert_history table
**Database Schema:**
```SQL
CREATE TABLE usage_alert_config (
    config_id VARCHAR(255) PRIMARY KEY,
    customer_id VARCHAR(255) NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    alert_type VARCHAR(50) NOT NULL, -- HIGH_USAGE, BUDGET_EXCEEDED, UNUSUAL_PATTERN
    threshold_type VARCHAR(50) NOT NULL, -- PERCENTAGE, ABSOLUTE_KWH, DOLLAR_AMOUNT
    threshold_value NUMERIC(10,2) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE usage_alert_history (
    alert_id VARCHAR(255) PRIMARY KEY,
    config_id VARCHAR(255) NOT NULL,
    triggered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    alert_message TEXT NOT NULL,
    actual_value NUMERIC(10,2) NOT NULL,
    threshold_value NUMERIC(10,2) NOT NULL,
    notification_sent BOOLEAN NOT NULL DEFAULT FALSE,
    FOREIGN KEY (config_id) REFERENCES usage_alert_config(config_id)
);
```
### 10.4 Usage Alert Job
**Location:** `customer-service/src/main/kotlin/com/example/usbilling/hr/jobs/`
**Tasks:**
* Create UsageAlertJob (daily scheduled job)
* Check usage against configured thresholds
* Trigger alerts when thresholds exceeded
* Send notifications via notification-service
**Files to Create:**
* `UsageAlertJob.kt`
* `UsageAlertService.kt`
***
## Sprint 11 (Weeks 21-22): Service Request Workflow
### 11.1 Service Request Schema
**Location:** `customer-service/src/main/resources/db/migration/customer/V025__create_service_request_tables.sql`
**Tasks:**
* Create service_request table
* Create service_request_appointment table
* Create service_request_status_history table
**Database Schema:**
```SQL
CREATE TABLE service_request (
    request_id VARCHAR(255) PRIMARY KEY,
    utility_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    request_type VARCHAR(50) NOT NULL, -- START_SERVICE, STOP_SERVICE, TRANSFER_SERVICE, MOVE_SERVICE, METER_TEST, RECONNECT
    service_type VARCHAR(50) NOT NULL, -- ELECTRIC, GAS, WATER
    service_address TEXT NOT NULL,
    requested_date DATE,
    status VARCHAR(50) NOT NULL, -- SUBMITTED, SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED
    priority VARCHAR(20) NOT NULL DEFAULT 'NORMAL', -- NORMAL, URGENT, EMERGENCY
    work_order_id VARCHAR(255),
    notes TEXT,
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    case_id VARCHAR(255) -- Link to case if needed
);
CREATE TABLE service_request_appointment (
    appointment_id VARCHAR(255) PRIMARY KEY,
    request_id VARCHAR(255) NOT NULL,
    scheduled_date DATE NOT NULL,
    time_window VARCHAR(50) NOT NULL, -- AM, PM, MORNING, AFTERNOON, SPECIFIC_TIME
    start_time TIME,
    end_time TIME,
    technician_id VARCHAR(255),
    status VARCHAR(50) NOT NULL, -- SCHEDULED, CONFIRMED, IN_PROGRESS, COMPLETED, CANCELLED, RESCHEDULED
    customer_notified BOOLEAN NOT NULL DEFAULT FALSE,
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (request_id) REFERENCES service_request(request_id)
);
```
### 11.2 Service Request API
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/http/`
**Tasks:**
* Create ServiceRequestController
* POST /api/customers/me/service-requests (submit request)
* GET /api/customers/me/service-requests (list requests)
* GET /api/customers/me/service-requests/{requestId} (request detail)
* PUT /api/customers/me/service-requests/{requestId}/cancel (cancel request)
* POST /api/customers/me/service-requests/{requestId}/reschedule (reschedule appointment)
**Files to Create:**
* `ServiceRequestController.kt`
* `SubmitServiceRequestRequest.kt`
* `ServiceRequestResponse.kt`
### 11.3 Service Request Workflow
**Location:** `customer-service/src/main/kotlin/com/example/usbilling/hr/service/`
**Tasks:**
* Create ServiceRequestService
* Implement request submission workflow
* Implement work order creation
* Implement appointment scheduling
* Implement status tracking
**Files to Create:**
* `ServiceRequestService.kt`
* `ServiceRequestRepository.kt`
***
## Sprint 12 (Weeks 23-24): Multi-Channel Support
### 12.1 Email Ticketing Integration
**Location:** `notification-service/src/main/kotlin/com/example/usbilling/notification/email/`
**Tasks:**
* Implement inbound email parsing
* Create case from email (parse subject, body, sender)
* Auto-respond with case number
* Route email to appropriate team
**Files to Create:**
* `InboundEmailProcessor.kt`
* `EmailToCaseConverter.kt`
### 12.2 SMS Two-Way Integration
**Location:** `notification-service/src/main/kotlin/com/example/usbilling/notification/sms/`
**Tasks:**
* Implement Twilio webhook for inbound SMS
* Support simple commands (BAL → balance, BILL → last bill, PAY → payment link)
* Create SMS command handler
* Respond with requested info
**Files to Create:**
* `InboundSmsController.kt`
* `SmsCommandHandler.kt`
### 12.3 IVR Integration (Basic)
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/ivr/`
**Tasks:**
* Create IVR API endpoints (for Twilio Studio integration)
* POST /api/ivr/balance (balance inquiry by phone)
* POST /api/ivr/last-bill (last bill amount)
* POST /api/ivr/outage (report outage via phone)
* Validate caller via account number + verification code
**Files to Create:**
* `IvrController.kt`
* `IvrAuthenticationService.kt`
***
## Phase 2 Deliverables Summary
**Completed Capabilities:**
* ✅ Payment plans (enrollment, tracking, broken plan handling)
* ✅ Auto-pay (enrollment, execution, failure handling)
* ✅ Dispute management (submission, investigation, resolution)
* ✅ Usage insights (comparison, projected bill)
* ✅ Usage alerts (high usage, budget exceeded)
* ✅ Service request management (start/stop service, meter test)
* ✅ Multi-channel support (email ticketing, SMS commands, IVR)
**Expected Impact:**
* 20% reduction in payment delinquency
* 15% increase in auto-pay enrollment
* Improved CSAT score (+10 points)
* Reduced dispute escalation to utility commission
***
# Phase 3: Advanced Features & CSR Tools (Months 7-12)
**Goal:** Operational efficiency and competitive differentiation
## Sprint 13 (Weeks 25-26): CSR Portal Service
### 13.1 Create CSR Portal Service Module
**Location:** `csr-portal-service/`
**Tasks:**
* Create Gradle module
* Configure port 8095
* Add Spring Boot, web-core dependencies
* Create CsrPortalApplication.kt
* Implement CSR authentication (internal JWT)
**Files to Create:**
* `csr-portal-service/build.gradle.kts`
* `csr-portal-service/src/main/kotlin/com/example/usbilling/csrportal/CsrPortalApplication.kt`
* `csr-portal-service/src/main/resources/application.yml`
### 13.2 Customer 360° View API
**Location:** `csr-portal-service/src/main/kotlin/com/example/usbilling/csrportal/http/`
**Tasks:**
* Create Customer360Controller
* GET /csr/customers/{customerId}/360 (aggregated customer view)
    * Customer profile
    * All accounts and services
    * Current balances
    * Payment history (last 12 months)
    * Bill history (last 12 months)
    * Case history
    * Interaction history
    * Payment plans
    * Auto-pay status
    * Disputes
* Aggregate data from multiple services (customer, billing, payments, case-management)
**Files to Create:**
* `Customer360Controller.kt`
* `Customer360Response.kt`
* `Customer360Service.kt`
### 13.3 CSR Quick Actions API
**Location:** `csr-portal-service/src/main/kotlin/com/example/usbilling/csrportal/http/`
**Tasks:**
* Create QuickActionsController
* POST /csr/customers/{customerId}/actions/apply-credit (apply one-time credit)
* POST /csr/customers/{customerId}/actions/waive-late-fee (waive late fee)
* POST /csr/customers/{customerId}/actions/send-email (send email to customer)
* POST /csr/customers/{customerId}/actions/create-case (create case on behalf of customer)
* POST /csr/customers/{customerId}/actions/extend-due-date (extend payment due date)
* Log all actions in customer_interaction table
**Files to Create:**
* `QuickActionsController.kt`
* `ApplyCreditRequest.kt`
* `QuickActionService.kt`
***
## Sprint 14 (Weeks 27-28): Appointment Scheduling
### 14.1 Technician Availability Schema
**Location:** `customer-service/src/main/resources/db/migration/customer/V026__create_technician_schedule_tables.sql`
**Tasks:**
* Create technician table
* Create technician_availability table
* Create appointment_slot table
**Database Schema:**
```SQL
CREATE TABLE technician (
    technician_id VARCHAR(255) PRIMARY KEY,
    utility_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    service_types TEXT[] NOT NULL, -- Services they can handle
    active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE technician_availability (
    availability_id VARCHAR(255) PRIMARY KEY,
    technician_id VARCHAR(255) NOT NULL,
    date DATE NOT NULL,
    time_window VARCHAR(50) NOT NULL, -- AM, PM, ALL_DAY
    available BOOLEAN NOT NULL DEFAULT TRUE,
    max_appointments INT NOT NULL DEFAULT 4,
    booked_appointments INT NOT NULL DEFAULT 0,
    FOREIGN KEY (technician_id) REFERENCES technician(technician_id)
);
```
### 14.2 Appointment Scheduling API
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/http/`
**Tasks:**
* Create AppointmentController
* GET /api/customers/me/appointments/availability (get available slots)
* POST /api/customers/me/appointments (book appointment)
* GET /api/customers/me/appointments (list appointments)
* PUT /api/customers/me/appointments/{appointmentId}/reschedule (reschedule)
* DELETE /api/customers/me/appointments/{appointmentId} (cancel)
**Files to Create:**
* `AppointmentController.kt`
* `AppointmentAvailabilityService.kt`
### 14.3 Appointment Notifications
**Location:** `notification-service/`
**Tasks:**
* Send appointment confirmation (1 day before)
* Send appointment reminder (2 hours before)
* Send technician en-route notification
* Send appointment completion confirmation
***
## Sprint 15 (Weeks 29-30): Assistance Programs
### 15.1 Assistance Program Schema
**Location:** `customer-service/src/main/resources/db/migration/customer/V027__create_assistance_program_tables.sql`
**Tasks:**
* Create assistance_program table
* Create program_enrollment table
* Create program_benefit_application table
**Database Schema:**
```SQL
CREATE TABLE assistance_program (
    program_id VARCHAR(255) PRIMARY KEY,
    utility_id VARCHAR(255) NOT NULL,
    program_code VARCHAR(50) NOT NULL,
    program_name VARCHAR(255) NOT NULL,
    program_type VARCHAR(50) NOT NULL, -- LIHEAP, SENIOR_DISCOUNT, MEDICAL_CERTIFICATE, CRISIS_ASSISTANCE
    benefit_type VARCHAR(50) NOT NULL, -- PERCENTAGE_DISCOUNT, FIXED_DISCOUNT, NO_DISCONNECT_PROTECTION
    benefit_value NUMERIC(10,2),
    eligibility_criteria TEXT,
    requires_verification BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE program_enrollment (
    enrollment_id VARCHAR(255) PRIMARY KEY,
    program_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL, -- PENDING, APPROVED, DENIED, EXPIRED, CANCELLED
    enrolled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    effective_from DATE NOT NULL,
    effective_to DATE,
    verification_document_id VARCHAR(255),
    verified_by VARCHAR(255),
    verified_at TIMESTAMP,
    FOREIGN KEY (program_id) REFERENCES assistance_program(program_id)
);
```
### 15.2 Assistance Program API
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/http/`
**Tasks:**
* Create AssistanceProgramController
* GET /api/customers/me/assistance-programs/available (list available programs)
* POST /api/customers/me/assistance-programs/{programId}/apply (apply for program)
* GET /api/customers/me/assistance-programs/enrollments (list enrollments)
* POST /api/customers/me/assistance-programs/enrollments/{enrollmentId}/documents (upload verification)
**Files to Create:**
* `AssistanceProgramController.kt`
* `AssistanceProgramService.kt`
***
## Sprint 16 (Weeks 31-32): Proactive Account Monitoring
### 16.1 Account Monitoring Jobs
**Location:** `customer-service/src/main/kotlin/com/example/usbilling/hr/jobs/`
**Tasks:**
* Create LeakDetectionJob (continuous water usage)
* Create TamperingDetectionJob (usage patterns)
* Create InactiveAccountJob (no usage for 60+ days)
* Create CreditLimitMonitoringJob (commercial accounts)
* Trigger cases automatically when issues detected
**Files to Create:**
* `LeakDetectionJob.kt`
* `TamperingDetectionJob.kt`
* `InactiveAccountJob.kt`
* `CreditLimitMonitoringJob.kt`
### 16.2 Proactive Notification Workflow
**Tasks:**
* Detect leak → create case → notify customer
* Detect tampering → create case → notify field operations
* Inactive account → notify customer (move/close account?)
* Credit limit exceeded → notify commercial customer
***
## Sprint 17 (Weeks 33-34): Document Management
### 17.1 Document Storage Schema
**Location:** `document-service/src/main/resources/db/migration/document/V001__create_document_tables.sql`
**Tasks:**
* Create document table
* Create document_version table
**Database Schema:**
```SQL
CREATE TABLE document (
    document_id VARCHAR(255) PRIMARY KEY,
    utility_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255),
    account_id VARCHAR(255),
    document_type VARCHAR(50) NOT NULL, -- BILL, PROOF_OF_OWNERSHIP, LEASE, ID, MEDICAL_CERTIFICATE
    document_name VARCHAR(500) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    storage_key VARCHAR(500) NOT NULL, -- S3 key or file path
    file_size_bytes BIGINT NOT NULL,
    uploaded_by VARCHAR(255),
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retention_until DATE -- For retention policy
);
```
### 17.2 Document Upload/Download API
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/http/`
**Tasks:**
* Create DocumentController
* POST /api/customers/me/documents (upload document)
* GET /api/customers/me/documents (list documents)
* GET /api/customers/me/documents/{documentId}/download (download document)
* DELETE /api/customers/me/documents/{documentId} (delete document)
**Files to Create:**
* `DocumentController.kt`
* `DocumentUploadService.kt`
### 17.3 Paperless Billing Enrollment
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/http/`
**Tasks:**
* Add paperless enrollment to notification preferences
* Stop generating paper bills when enrolled
* Send email notification instead of mailing bill
***
## Sprint 18 (Weeks 35-36): Customer Feedback & Surveys
### 18.1 Survey Schema
**Location:** `customer-service/src/main/resources/db/migration/customer/V028__create_survey_tables.sql`
**Tasks:**
* Create survey_template table
* Create survey_response table
**Database Schema:**
```SQL
CREATE TABLE survey_template (
    template_id VARCHAR(255) PRIMARY KEY,
    utility_id VARCHAR(255) NOT NULL,
    survey_type VARCHAR(50) NOT NULL, -- CSAT, NPS, POST_INTERACTION
    title VARCHAR(255) NOT NULL,
    questions JSONB NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE survey_response (
    response_id VARCHAR(255) PRIMARY KEY,
    template_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    interaction_id VARCHAR(255), -- Link to interaction if post-interaction survey
    case_id VARCHAR(255),
    responses JSONB NOT NULL,
    score INT, -- CSAT or NPS score
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (template_id) REFERENCES survey_template(template_id)
);
```
### 18.2 Survey API
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/http/`
**Tasks:**
* Create SurveyController
* GET /api/surveys/{surveyId} (get survey for customer)
* POST /api/surveys/{surveyId}/responses (submit survey response)
* Trigger post-interaction surveys automatically
**Files to Create:**
* `SurveyController.kt`
* `SurveyService.kt`
***
## Sprint 19-20 (Weeks 37-40): Mobile App Backend Support
### 19.1 Mobile API Gateway
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/mobile/`
**Tasks:**
* Create mobile-optimized endpoints (reduced payload)
* Implement push notification registration
* Add mobile session management
* Implement biometric authentication support (store device tokens)
**Files to Create:**
* `MobileApiController.kt`
* `PushNotificationService.kt`
* `DeviceRegistrationController.kt`
### 19.2 Push Notification Support
**Location:** `notification-service/src/main/kotlin/com/example/usbilling/notification/push/`
**Tasks:**
* Integrate with Firebase Cloud Messaging (FCM)
* Implement push notification provider
* Send push notifications for critical alerts (outage, payment due, low balance)
**Files to Create:**
* `FcmPushProvider.kt`
* `PushNotificationController.kt`
***
## Sprint 21 (Weeks 41-42): Energy Efficiency Features
### 21.1 Energy Audit Scheduling
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/http/`
**Tasks:**
* Create EnergyAuditController
* POST /api/customers/me/energy-audits/request (request home energy audit)
* Schedule appointment with energy auditor
* Track audit completion and recommendations
### 21.2 Rebate Program Management
**Location:** `customer-service/src/main/resources/db/migration/customer/V029__create_rebate_program_tables.sql`
**Tasks:**
* Create rebate_program table
* Create rebate_application table
* Implement rebate application workflow
* Track rebate approval and payment
***
## Sprint 22 (Weeks 43-44): Advanced Metering (AMI) Support
### 22.1 Interval Data Access
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/http/`
**Tasks:**
* Create IntervalDataController
* GET /api/customers/me/accounts/{accountId}/interval-data (15-min interval data)
* GET /api/customers/me/accounts/{accountId}/real-time-usage (current usage)
* Integrate with meter-reading-service
### 22.2 Remote Connect/Disconnect
**Location:** `customer-service/src/main/kotlin/com/example/usbilling/hr/ami/`
**Tasks:**
* Implement AMI command service
* POST /utilities/{utilityId}/meters/{meterId}/connect (remote connect)
* POST /utilities/{utilityId}/meters/{meterId}/disconnect (remote disconnect)
* Log all remote commands in audit trail
***
## Sprint 23-24 (Weeks 45-48): Voice Assistant Integration
### 23.1 Alexa Skill Backend
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/voice/`
**Tasks:**
* Create AlexaController (webhook endpoint)
* Implement intents:
    * GetBalance → "What's my current balance?"
    * GetLastBill → "What was my last bill?"
    * GetUsage → "How much electricity did I use this month?"
    * ReportOutage → "Report a power outage"
    * MakePayment → "Make a payment" (redirect to web for security)
* Link Alexa account to customer account (OAuth)
**Files to Create:**
* `AlexaController.kt`
* `AlexaIntentHandler.kt`
### 23.2 Google Assistant Integration
**Location:** `customer-portal-service/src/main/kotlin/com/example/usbilling/portal/voice/`
**Tasks:**
* Create GoogleAssistantController
* Implement similar intents as Alexa
* Use Actions on Google SDK
***
## Phase 3 Deliverables Summary
**Completed Capabilities:**
* ✅ CSR portal with 360° customer view
* ✅ CSR quick actions (apply credit, waive fees, etc.)
* ✅ Appointment scheduling with technician availability
* ✅ Assistance program enrollment and tracking
* ✅ Proactive account monitoring (leak detection, tampering)
* ✅ Document management (upload, download, retention)
* ✅ Customer feedback and surveys
* ✅ Mobile app backend support with push notifications
* ✅ Energy efficiency features (audits, rebates)
* ✅ Advanced metering support (interval data, remote control)
* ✅ Voice assistant integration (Alexa, Google Assistant)
**Expected Impact:**
* 50% improvement in CSR handle time
* 25% increase in assistance program enrollment
* Best-in-class customer experience (industry-leading CSAT)
* Competitive differentiation with advanced features
***
# Infrastructure & Operations
## Docker Compose Updates
**Location:** `docker-compose.billing.yml`
**Tasks:**
* Add customer-portal-service (port 8090)
* Add notification-service (port 8091)
* Add case-management-service (port 8092)
* Add outage-service (port 8093)
* Add document-service (port 8094)
* Add csr-portal-service (port 8095)
* Add RabbitMQ for notification queuing
* Add Redis for caching
* Update health check dependencies
## Kubernetes Manifests
**Location:** `deploy/k8s/base/`
**Tasks:**
* Create deployment, service, and configmap for each new service
* Add network policies for new service communication
* Add horizontal pod autoscaling (HPA)
* Update ingress routing rules
## CI/CD Pipeline Updates
**Location:** `.github/workflows/`
**Tasks:**
* Add build jobs for new services
* Add E2E tests for customer portal workflows
* Add integration tests for notification delivery
* Update deployment pipeline
## Monitoring & Alerting
**Location:** `deploy/observability/`
**Tasks:**
* Add Grafana dashboards for new services
* Add Prometheus alerts for:
    * Notification delivery failures
    * Payment processing failures
    * High case volume
    * Outage reporting spikes
    * Auto-pay failure rate
***
# Testing Strategy
## Unit Tests
**Coverage Target:** 80% minimum per module
**Focus Areas:**
* Payment plan calculation logic
* Auto-pay execution logic
* Dispute resolution workflow
* Usage comparison calculations
* Bill PDF generation
## Integration Tests
**Focus Areas:**
* Customer portal → customer-service integration
* Customer portal → billing-orchestrator integration
* Notification service → email/SMS provider integration
* Case management → notification integration
## E2E Tests
**Location:** `e2e-tests/src/test/kotlin/com/example/usbilling/e2e/`
**Scenarios:**
1. Customer views bill and makes payment
2. Customer enrolls in auto-pay
3. Customer submits billing dispute
4. Customer reports outage and checks status
5. Customer enrolls in payment plan
6. CSR applies credit to customer account
7. Notification is sent when bill is finalized
8. Auto-pay executes successfully
**Files to Create:**
* `CustomerPortalE2ETest.kt`
* `PaymentPlanE2ETest.kt`
* `DisputeWorkflowE2ETest.kt`
* `OutageManagementE2ETest.kt`
* `NotificationDeliveryE2ETest.kt`
## Load Testing
**Location:** `benchmarks/k6/`
**Scenarios:**
* 10,000 concurrent customers viewing bills
* 5,000 concurrent payments
* 1,000 notifications/second
* Bill generation for 100,000 customers
***
# Documentation Updates
## Architecture Documentation
**Location:** `docs/`
**Files to Update:**
* `architecture.md` - Add customer portal and notification architecture
* `runtime-architecture.md` - Update service topology diagram
* `docs/ops/enterprise-readiness-capability-backlog.md` - Mark capabilities as Done
## API Documentation
**Location:** `docs/api/`
**Files to Create:**
* `customer-portal-api.md` - Customer-facing API documentation
* `notification-service-api.md` - Notification API documentation
* `case-management-api.md` - Case management API documentation
* `outage-service-api.md` - Outage management API documentation
* `csr-portal-api.md` - CSR portal API documentation
## Operational Runbooks
**Location:** `docs/ops/`
**Files to Create:**
* `customer-portal-operations.md` - Customer portal troubleshooting
* `notification-delivery-troubleshooting.md` - Notification issues
* `payment-plan-monitoring.md` - Payment plan health monitoring
* `auto-pay-troubleshooting.md` - Auto-pay failure handling
* `outage-management-procedures.md` - Outage management best practices
***
# Success Metrics
## Phase 1 Success Criteria
* Customer portal adoption: 40% of active customers within 3 months
* CSR call volume reduction: 30-40%
* Payment timeliness improvement: 15%
* Bill PDF generation success rate: 99.5%
* Notification delivery rate: 98%
* Outage reporting: 60% of outages reported via portal (vs. phone)
## Phase 2 Success Criteria
* Auto-pay enrollment: 25% of active customers
* Payment plan success rate: 75% completion rate
* Dispute resolution time: Average 5 days (from 14 days)
* Projected bill accuracy: 90% within $10 of actual bill
* Service request completion: 95% within SLA
## Phase 3 Success Criteria
* CSR handle time reduction: 50%
* Customer satisfaction (CSAT): 85+ score
* Net Promoter Score (NPS): 50+
* Mobile app downloads: 30% of customer base
* Assistance program enrollment: 90% of eligible customers
***
# Risk Mitigation
## Technical Risks
1. **Payment processing integration complexity**
    * Mitigation: Use established providers (Stripe, Square) with proven SDKs
    * Implement comprehensive error handling and retries
2. **Notification delivery reliability**
    * Mitigation: Use enterprise notification providers (SendGrid, Twilio)
    * Implement queue-based delivery with retries
    * Monitor delivery rates and alert on degradation
3. **Performance at scale**
    * Mitigation: Load test early and often
    * Implement caching (Redis)
    * Use database read replicas for reporting queries
## Operational Risks
1. **Customer adoption**
    * Mitigation: Launch awareness campaign
    * Provide CSR training on portal features
    * Offer incentives (auto-pay discount, paperless credit)
2. **CSR resistance to new tools**
    * Mitigation: Involve CSRs in design process
    * Provide comprehensive training
    * Show time savings with real examples
## Security Risks
1. **Customer data exposure**
    * Mitigation: Implement proper authentication and authorization
    * Use PII redaction in logs
    * Encrypt data at rest and in transit
    * Regular security audits
***
# Conclusion
This comprehensive plan delivers a modern customer service platform over 12 months through three distinct phases. Each phase builds on the previous one, delivering incremental value while minimizing risk.
**Key Deliverables:**
* 6 new microservices (customer-portal, notification, case-management, outage, document, csr-portal)
* 50+ REST API endpoints
* Multi-channel communication (email, SMS, push, voice)
* Complete case management and dispute workflow
* Payment plans and auto-pay
* CSR tools and 360° customer view
* Mobile app backend support
* Advanced features (voice assistants, AMI integration, energy efficiency)
**Expected Outcomes:**
* 30-40% reduction in CSR call volume
* 20% reduction in payment delinquency
* 50% improvement in CSR efficiency
* Industry-leading customer satisfaction
* Competitive differentiation
The platform will transform the utility from reactive customer service to proactive customer engagement, reducing costs while dramatically improving the customer experience.