package com.example.usbilling.hr.http

import com.example.usbilling.hr.domain.*
import com.example.usbilling.hr.service.BitemporalCustomerService
import com.example.usbilling.hr.service.CustomerAccountUpdate
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

/**
 * REST API for bitemporal customer management using SCD2 pattern.
 * 
 * All operations use append-only pattern:
 * - POST: Create new version
 * - PUT: Close current + insert new version
 * - GET with ?asOfDate: Query historical data
 * - GET with ?includeHistory: Retrieve all versions
 * 
 * Path prefix: /v2/ for safe parallel deployment with v1 CRUD endpoints.
 */
@RestController
@RequestMapping("/v2/utilities/{utilityId}")
class BitemporalCustomerController(
    private val bitemporalCustomerService: BitemporalCustomerService
) {

    // ========== Customer Account Operations ==========

    /**
     * Create a new customer account - append-only.
     * 
     * POST /v2/utilities/{utilityId}/customers
     */
    @PostMapping("/customers")
    fun createCustomer(
        @PathVariable utilityId: String,
        @RequestBody request: CreateCustomerRequestV2
    ): ResponseEntity<CustomerAccountResponse> {
        val customer = bitemporalCustomerService.createCustomerAccount(
            utilityId = utilityId,
            accountNumber = request.accountNumber,
            customerName = request.customerName,
            serviceAddress = request.serviceAddress,
            customerClass = request.customerClass
        )
        
        return ResponseEntity.status(HttpStatus.CREATED).body(CustomerAccountResponse.from(customer))
    }

    /**
     * Update customer account - append new version, close old.
     * 
     * PUT /v2/utilities/{utilityId}/customers/{customerId}
     */
    @PutMapping("/customers/{customerId}")
    fun updateCustomer(
        @PathVariable utilityId: String,
        @PathVariable customerId: String,
        @RequestBody updates: CustomerAccountUpdate
    ): ResponseEntity<CustomerAccountResponse> {
        val updated = bitemporalCustomerService.updateCustomerAccount(customerId, updates)
        return ResponseEntity.ok(CustomerAccountResponse.from(updated))
    }

    /**
     * Get customer account as of specific date (default: today).
     * 
     * GET /v2/utilities/{utilityId}/customers/{customerId}?asOfDate=2025-01-15
     */
    @GetMapping("/customers/{customerId}")
    fun getCustomer(
        @PathVariable utilityId: String,
        @PathVariable customerId: String,
        @RequestParam(required = false) asOfDate: String?
    ): ResponseEntity<CustomerAccountResponse> {
        val date = asOfDate?.let { LocalDate.parse(it) } ?: LocalDate.now()
        
        val customer = bitemporalCustomerService.getCustomerAccountAsOf(customerId, date)
            ?: return ResponseEntity.notFound().build()
        
        // Verify utility ID matches
        if (customer.utilityId != utilityId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        
        return ResponseEntity.ok(CustomerAccountResponse.from(customer))
    }

    /**
     * Get complete history of a customer account.
     * 
     * GET /v2/utilities/{utilityId}/customers/{customerId}/history
     */
    @GetMapping("/customers/{customerId}/history")
    fun getCustomerHistory(
        @PathVariable utilityId: String,
        @PathVariable customerId: String
    ): ResponseEntity<List<CustomerAccountResponse>> {
        val history = bitemporalCustomerService.getCustomerAccountHistory(customerId)
        
        if (history.isEmpty()) {
            return ResponseEntity.notFound().build()
        }
        
        // Verify utility ID matches (check first version)
        if (history.first().utilityId != utilityId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        
        return ResponseEntity.ok(history.map { CustomerAccountResponse.from(it) })
    }

    /**
     * List all customers for a utility (current versions only).
     * 
     * GET /v2/utilities/{utilityId}/customers
     */
    @GetMapping("/customers")
    fun listCustomers(@PathVariable utilityId: String): ResponseEntity<List<CustomerAccountResponse>> {
        val customers = bitemporalCustomerService.listCustomersByUtility(utilityId)
        return ResponseEntity.ok(customers.map { CustomerAccountResponse.from(it) })
    }

    // ========== Service Point Operations ==========

    /**
     * Create a service point for an account.
     * 
     * POST /v2/utilities/{utilityId}/customers/{customerId}/service-points
     */
    @PostMapping("/customers/{customerId}/service-points")
    fun createServicePoint(
        @PathVariable utilityId: String,
        @PathVariable customerId: String,
        @RequestBody request: CreateServicePointRequest
    ): ResponseEntity<ServicePointEffective> {
        // TODO: Verify customer exists and belongs to utility
        
        val servicePoint = bitemporalCustomerService.createServicePoint(
            accountId = customerId,
            serviceAddress = request.serviceAddress,
            serviceType = request.serviceType
        )
        
        return ResponseEntity.status(HttpStatus.CREATED).body(servicePoint)
    }

    // ========== Meter Operations ==========

    /**
     * Create a meter for a service point.
     * 
     * POST /v2/utilities/{utilityId}/customers/{customerId}/meters
     */
    @PostMapping("/customers/{customerId}/meters")
    fun addMeter(
        @PathVariable utilityId: String,
        @PathVariable customerId: String,
        @RequestBody request: AddMeterRequestV2
    ): ResponseEntity<MeterResponse> {
        // Create a service point first if not provided
        // For simplicity, create service point with same address as customer
        val customer = bitemporalCustomerService.getCustomerAccountAsOf(customerId, LocalDate.now())
            ?: return ResponseEntity.notFound().build()
        
        // Reconstruct address from billing address fields
        val serviceAddress = "${customer.billingAddressLine1}|${customer.billingAddressLine2 ?: ""}|${customer.billingCity}|${customer.billingState}|${customer.billingPostalCode}"
        
        val servicePoint = bitemporalCustomerService.createServicePoint(
            accountId = customerId,
            serviceAddress = serviceAddress,
            serviceType = request.utilityServiceType
        )
        
        val meter = bitemporalCustomerService.createMeter(
            servicePointId = servicePoint.servicePointId,
            utilityServiceType = request.utilityServiceType,
            meterNumber = request.meterNumber
        )
        
        return ResponseEntity.status(HttpStatus.CREATED).body(MeterResponse.from(meter))
    }

    /**
     * Get meters for a customer account.
     * 
     * GET /v2/utilities/{utilityId}/customers/{customerId}/meters?asOfDate=2025-01-15
     */
    @GetMapping("/customers/{customerId}/meters")
    fun listMeters(
        @PathVariable utilityId: String,
        @PathVariable customerId: String,
        @RequestParam(required = false) asOfDate: String?
    ): ResponseEntity<List<MeterResponse>> {
        val date = asOfDate?.let { LocalDate.parse(it) } ?: LocalDate.now()
        val meters = bitemporalCustomerService.getMetersByAccount(customerId, date)
        return ResponseEntity.ok(meters.map { MeterResponse.from(it) })
    }

    // ========== Billing Period Operations ==========

    /**
     * Create a billing period for a customer.
     * 
     * POST /v2/utilities/{utilityId}/customers/{customerId}/billing-periods
     */
    @PostMapping("/customers/{customerId}/billing-periods")
    fun createBillingPeriod(
        @PathVariable utilityId: String,
        @PathVariable customerId: String,
        @RequestBody request: CreateBillingPeriodRequestV2
    ): ResponseEntity<BillingPeriodEffective> {
        val period = bitemporalCustomerService.createBillingPeriod(
            accountId = customerId,
            startDate = request.startDate,
            endDate = request.endDate,
            dueDate = request.dueDate ?: request.endDate.plusDays(20),
            status = request.status ?: "OPEN"
        )
        
        return ResponseEntity.status(HttpStatus.CREATED).body(period)
    }

    /**
     * Update billing period status - append new version.
     * 
     * PUT /v2/utilities/{utilityId}/billing-periods/{periodId}/status
     */
    @PutMapping("/billing-periods/{periodId}/status")
    fun updateBillingPeriodStatus(
        @PathVariable utilityId: String,
        @PathVariable periodId: String,
        @RequestBody request: UpdatePeriodStatusRequest
    ): ResponseEntity<BillingPeriodEffective> {
        val updated = bitemporalCustomerService.updateBillingPeriodStatus(
            periodId = periodId,
            newStatus = request.status
        )
        return ResponseEntity.ok(updated)
    }

    /**
     * List billing periods for a customer.
     * 
     * GET /v2/utilities/{utilityId}/customers/{customerId}/billing-periods?asOfDate=2025-01-15
     */
    @GetMapping("/customers/{customerId}/billing-periods")
    fun listBillingPeriods(
        @PathVariable utilityId: String,
        @PathVariable customerId: String,
        @RequestParam(required = false) asOfDate: String?
    ): ResponseEntity<List<BillingPeriodEffective>> {
        val date = asOfDate?.let { LocalDate.parse(it) } ?: LocalDate.now()
        val periods = bitemporalCustomerService.getBillingPeriodsByAccount(customerId, date)
        return ResponseEntity.ok(periods)
    }
}

// ========== Request/Response DTOs ==========

/**
 * Simplified response DTO for customer accounts that matches test expectations.
 */
data class CustomerAccountResponse(
    val accountId: String,
    val utilityId: String,
    val accountNumber: String,
    val customerName: String,
    val serviceAddress: String,
    val customerClass: String,
    val active: Boolean,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate,
    val systemFrom: java.time.Instant,
    val systemTo: java.time.Instant
) {
    companion object {
        fun from(entity: CustomerAccountEffective): CustomerAccountResponse {
            val serviceAddress = "${entity.billingAddressLine1}|${entity.billingAddressLine2 ?: ""}|${entity.billingCity}|${entity.billingState}|${entity.billingPostalCode}"
            return CustomerAccountResponse(
                accountId = entity.accountId,
                utilityId = entity.utilityId,
                accountNumber = entity.accountNumber,
                customerName = entity.holderName,
                serviceAddress = serviceAddress,
                customerClass = entity.accountType,
                active = entity.accountStatus == "ACTIVE",
                effectiveFrom = entity.effectiveFrom,
                effectiveTo = entity.effectiveTo,
                systemFrom = entity.systemFrom,
                systemTo = entity.systemTo
            )
        }
    }
}

/**
 * Simplified response DTO for meters that matches test expectations.
 */
data class MeterResponse(
    val meterId: String,
    val utilityServiceType: String,
    val meterNumber: String,
    val active: Boolean,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate,
    val systemFrom: java.time.Instant,
    val systemTo: java.time.Instant
) {
    companion object {
        fun from(entity: MeterEffective): MeterResponse {
            // Map meterType back to service type
            val serviceType = when {
                entity.meterType.startsWith("ELECTRIC", ignoreCase = true) -> "ELECTRIC"
                entity.meterType.startsWith("GAS", ignoreCase = true) -> "GAS"
                entity.meterType.startsWith("WATER", ignoreCase = true) -> "WATER"
                else -> "ELECTRIC"
            }
            
            return MeterResponse(
                meterId = entity.meterId,
                utilityServiceType = serviceType,
                meterNumber = entity.meterSerial,
                active = entity.meterStatus == "ACTIVE",
                effectiveFrom = entity.effectiveFrom,
                effectiveTo = entity.effectiveTo,
                systemFrom = entity.systemFrom,
                systemTo = entity.systemTo
            )
        }
    }
}

data class CreateCustomerRequestV2(
    val accountNumber: String,
    val customerName: String,
    val serviceAddress: String,
    val customerClass: String?
)

data class CreateServicePointRequest(
    val serviceAddress: String,
    val serviceType: String
)

data class AddMeterRequestV2(
    val utilityServiceType: String,
    val meterNumber: String
)

data class CreateBillingPeriodRequestV2(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val dueDate: LocalDate?,
    val status: String?
)

data class UpdatePeriodStatusRequest(
    val status: String
)
