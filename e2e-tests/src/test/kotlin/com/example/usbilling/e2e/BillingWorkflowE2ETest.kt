package com.example.usbilling.e2e

import org.hamcrest.Matchers.*
import org.junit.jupiter.api.*
import java.time.LocalDate

/**
 * End-to-end tests for the complete billing workflow.
 * 
 * Tests the integration between all five microservices:
 * - customer-service: Customer and meter management
 * - rate-service: Tariff and rate retrieval
 * - regulatory-service: Regulatory charge retrieval
 * - billing-worker-service: Bill calculation
 * - billing-orchestrator-service: Bill lifecycle management
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BillingWorkflowE2ETest : BaseE2ETest() {
    
    companion object {
        private lateinit var utilityId: String
        private lateinit var customerId: String
        private lateinit var meterId: String
        private lateinit var billingPeriodId: String
        private lateinit var billId: String
    }
    
    @Test
    @Order(1)
    @DisplayName("Should create a customer in customer-service")
    fun testCreateCustomer() {
        val requestBody = mapOf(
            "accountNumber" to "ACCT-12345",
            "customerName" to "Jane Smith",
            "serviceAddress" to "123 Main St | Apt 4B | Detroit | MI | 48201",
            "customerClass" to "RESIDENTIAL"
        )
        
        val response = customerService()
            .body(requestBody)
            .`when`()
            .post("/utilities/UTIL-001/customers")
            .then()
            .statusCode(201)
            .body("utilityId", equalTo("UTIL-001"))
            .body("accountNumber", equalTo("ACCT-12345"))
            .body("customerName", equalTo("Jane Smith"))
            .body("active", equalTo(true))
            .body("customerId", notNullValue())
            .extract()
        
        customerId = response.path("customerId")
        utilityId = "UTIL-001"
        
        println("Created customer: $customerId")
    }
    
    @Test
    @Order(2)
    @DisplayName("Should create a meter for the customer")
    fun testCreateMeter() {
        val requestBody = mapOf(
            "meterNumber" to "MTR-67890",
            "utilityServiceType" to "ELECTRIC",
            "installDate" to "2024-01-01"
        )
        
        val response = customerService()
            .body(requestBody)
            .`when`()
            .post("/utilities/$utilityId/customers/$customerId/meters")
            .then()
            .statusCode(201)
            .body("meterNumber", equalTo("MTR-67890"))
            .body("utilityServiceType", equalTo("ELECTRIC"))
            .body("active", equalTo(true))
            .body("meterId", notNullValue())
            .extract()
        
        meterId = response.path("meterId")
        
        println("Created meter: $meterId")
    }
    
    @Test
    @Order(3)
    @DisplayName("Should create a billing period")
    fun testCreateBillingPeriod() {
        val requestBody = mapOf(
            "startDate" to "2025-01-01",
            "endDate" to "2025-01-31",
            "status" to "OPEN"
        )
        
        val response = customerService()
            .body(requestBody)
            .`when`()
            .post("/utilities/$utilityId/customers/$customerId/billing-periods")
            .then()
            .statusCode(201)
            .body("customerId", equalTo(customerId))
            .body("startDate", equalTo("2025-01-01"))
            .body("endDate", equalTo("2025-01-31"))
            .body("status", equalTo("OPEN"))
            .body("periodId", notNullValue())
            .extract()
        
        billingPeriodId = response.path("periodId")
        
        println("Created billing period: $billingPeriodId")
    }
    
    @Test
    @Order(4)
    @DisplayName("Should record meter reads for the billing period")
    fun testRecordMeterReads() {
        // Opening read
        val openingReadBody = mapOf(
            "meterId" to meterId,
            "readDate" to "2025-01-01",
            "readingValue" to 10000.0,
            "readingType" to "OPENING"
        )
        
        customerService()
            .body(openingReadBody)
            .`when`()
            .post("/utilities/$utilityId/customers/$customerId/meter-reads")
            .then()
            .statusCode(201)
            .body("readingValue", equalTo(10000.0f))
            .body("readingType", equalTo("OPENING"))
        
        // Closing read
        val closingReadBody = mapOf(
            "meterId" to meterId,
            "readDate" to "2025-01-31",
            "readingValue" to 10500.0,
            "readingType" to "CLOSING"
        )
        
        customerService()
            .body(closingReadBody)
            .`when`()
            .post("/utilities/$utilityId/customers/$customerId/meter-reads")
            .then()
            .statusCode(201)
            .body("readingValue", equalTo(10500.0f))
            .body("readingType", equalTo("CLOSING"))
        
        println("Recorded meter reads: 10000 kWh -> 10500 kWh (500 kWh usage)")
    }
    
    @Test
    @Order(5)
    @DisplayName("Should retrieve rate context from rate-service")
    fun testGetRateContext() {
        rateService()
            .queryParam("utilityId", utilityId)
            .queryParam("state", "MI")
            .queryParam("asOfDate", "2025-01-15")
            .`when`()
            .get("/utilities/$utilityId/rate-context")
            .then()
            .statusCode(200)
            .body("utilityId", equalTo(utilityId))
            .body("applicableTariffs", notNullValue())
        
        println("Retrieved rate context for MI utility")
    }
    
    @Test
    @Order(6)
    @DisplayName("Should retrieve regulatory charges from regulatory-service")
    fun testGetRegulatoryCharges() {
        regulatoryService()
            .queryParam("state", "MI")
            .queryParam("asOfDate", "2025-01-15")
            .`when`()
            .get("/utilities/$utilityId/regulatory-charges")
            .then()
            .statusCode(200)
            .body("state", equalTo("MI"))
            .body("charges", notNullValue())
            .body("charges.size()", greaterThan(0))
        
        println("Retrieved regulatory charges for MI")
    }
    
    @Test
    @Order(7)
    @DisplayName("Should create a draft bill in billing-orchestrator-service")
    fun testCreateDraftBill() {
        val requestBody = mapOf(
            "customerId" to customerId,
            "billingPeriodId" to billingPeriodId,
            "billDate" to LocalDate.now().toString()
        )
        
        val response = billingOrchestratorService()
            .body(requestBody)
            .`when`()
            .post("/utilities/$utilityId/bills")
            .then()
            .statusCode(201)
            .body("utilityId", equalTo(utilityId))
            .body("customerId", equalTo(customerId))
            .body("status", equalTo("DRAFT"))
            .body("billId", notNullValue())
            .extract()
        
        billId = response.path("billId")
        
        println("Created draft bill: $billId")
    }
    
    @Test
    @Order(8)
    @DisplayName("Should retrieve bill with details")
    fun testGetBillDetails() {
        billingOrchestratorService()
            .`when`()
            .get("/utilities/$utilityId/bills/$billId")
            .then()
            .statusCode(200)
            .body("billId", equalTo(billId))
            .body("utilityId", equalTo(utilityId))
            .body("customerId", equalTo(customerId))
            .body("status", equalTo("DRAFT"))
            .body("lines", notNullValue())
            .body("events", notNullValue())
        
        println("Retrieved bill details for: $billId")
    }
    
    @Test
    @Order(9)
    @DisplayName("Should list customer bills")
    fun testListCustomerBills() {
        billingOrchestratorService()
            .`when`()
            .get("/utilities/$utilityId/customers/$customerId/bills")
            .then()
            .statusCode(200)
            .body("size()", greaterThanOrEqualTo(1))
            .body("[0].billId", equalTo(billId))
            .body("[0].customerId", equalTo(customerId))
        
        println("Listed bills for customer: $customerId")
    }
    
    @Test
    @Order(10)
    @DisplayName("Should void a bill")
    fun testVoidBill() {
        val requestBody = mapOf(
            "reason" to "Test void - E2E test cleanup"
        )
        
        billingOrchestratorService()
            .body(requestBody)
            .`when`()
            .post("/utilities/$utilityId/bills/$billId/void")
            .then()
            .statusCode(200)
            .body("billId", equalTo(billId))
            .body("status", equalTo("VOIDED"))
        
        println("Voided bill: $billId")
    }
    
    @Test
    @Order(11)
    @DisplayName("Should verify health endpoints for all services")
    fun testServiceHealthChecks() {
        // Customer service
        customerService()
            .`when`()
            .get("/actuator/health")
            .then()
            .statusCode(200)
            .body("status", equalTo("UP"))
        
        // Rate service
        rateService()
            .`when`()
            .get("/actuator/health")
            .then()
            .statusCode(200)
            .body("status", equalTo("UP"))
        
        // Regulatory service
        regulatoryService()
            .`when`()
            .get("/actuator/health")
            .then()
            .statusCode(200)
            .body("status", equalTo("UP"))
        
        // Billing worker service
        billingWorkerService()
            .`when`()
            .get("/health")
            .then()
            .statusCode(200)
        
        // Billing orchestrator service
        billingOrchestratorService()
            .`when`()
            .get("/actuator/health")
            .then()
            .statusCode(200)
            .body("status", equalTo("UP"))
        
        println("All services health checks passed")
    }
}
