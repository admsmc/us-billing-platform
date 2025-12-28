package com.example.usbilling.worker.http

import com.example.usbilling.billing.model.BillResult
import com.example.usbilling.billing.model.BillingPeriod
import com.example.usbilling.billing.model.BillingFrequency
import com.example.usbilling.billing.model.ServiceType
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.worker.service.BillingComputationService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

/**
 * REST API for billing worker service.
 * Provides demo/dry-run endpoints for testing bill computation.
 */
@RestController
@RequestMapping("/billing-worker")
class BillingWorkerController(
    private val billingComputationService: BillingComputationService
) {

    /**
     * Synchronous dry-run bill computation.
     * Calls all three services and runs BillingEngine.
     */
    @PostMapping("/dry-run-bill")
    fun dryRunBill(@RequestBody request: DryRunBillRequest): ResponseEntity<BillResult> {
        val billResult = billingComputationService.computeBill(
            utilityId = UtilityId(request.utilityId),
            customerId = CustomerId(request.customerId),
            billingPeriodId = request.billingPeriodId,
            serviceState = request.serviceState
        )

        return if (billResult != null) {
            ResponseEntity.ok(billResult)
        } else {
            ResponseEntity.status(500).build()
        }
    }

    /**
     * Simple dry-run with provided usage amount.
     * Creates a synthetic billing period for testing.
     */
    @PostMapping("/dry-run-simple")
    fun dryRunSimple(@RequestBody request: SimpleDryRunRequest): ResponseEntity<BillResult> {
        val billingPeriod = BillingPeriod(
            id = "dry-run-period",
            utilityId = UtilityId(request.utilityId),
            startDate = LocalDate.now().minusMonths(1),
            endDate = LocalDate.now(),
            billDate = LocalDate.now(),
            dueDate = LocalDate.now().plusDays(20),
            frequency = BillingFrequency.MONTHLY
        )

        val serviceType = parseServiceType(request.serviceType)

        val billResult = billingComputationService.computeSingleServiceBill(
            utilityId = UtilityId(request.utilityId),
            customerId = CustomerId(request.customerId),
            serviceType = serviceType,
            usage = request.usage,
            billingPeriod = billingPeriod,
            serviceState = request.serviceState
        )

        return if (billResult != null) {
            ResponseEntity.ok(billResult)
        } else {
            ResponseEntity.status(500).build()
        }
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    fun health(): ResponseEntity<HealthResponse> {
        return ResponseEntity.ok(HealthResponse("UP", "Billing Worker Service"))
    }

    private fun parseServiceType(value: String): ServiceType {
        return try {
            ServiceType.valueOf(value.uppercase())
        } catch (e: IllegalArgumentException) {
            ServiceType.ELECTRIC
        }
    }
}

/**
 * Request to compute a bill with full service integration.
 */
data class DryRunBillRequest(
    val utilityId: String,
    val customerId: String,
    val billingPeriodId: String,
    val serviceState: String
)

/**
 * Request for simple dry-run with provided usage.
 */
data class SimpleDryRunRequest(
    val utilityId: String,
    val customerId: String,
    val serviceType: String,
    val usage: Double,
    val serviceState: String
)

/**
 * Health check response.
 */
data class HealthResponse(
    val status: String,
    val service: String
)
