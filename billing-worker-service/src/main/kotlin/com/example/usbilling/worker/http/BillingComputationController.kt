package com.example.usbilling.worker.http

import com.example.usbilling.billing.model.BillResult
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.worker.service.BillingComputationService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * HTTP controller for bill computation requests.
 */
@RestController
class BillingComputationController(
    private val billingComputationService: BillingComputationService,
) {

    /**
     * Compute a bill synchronously.
     * Called by billing-orchestrator-service.
     */
    @PostMapping("/compute-bill")
    fun computeBill(@RequestBody request: ComputeBillRequest): ResponseEntity<BillResult> {
        val billResult = billingComputationService.computeBill(
            utilityId = UtilityId(request.utilityId),
            customerId = CustomerId(request.customerId),
            billingPeriodId = request.billingPeriodId,
            serviceState = request.serviceState,
        )

        return if (billResult != null) {
            ResponseEntity.ok(billResult)
        } else {
            ResponseEntity.badRequest().build()
        }
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    fun health(): ResponseEntity<Map<String, String>> = ResponseEntity.ok(mapOf("status" to "UP"))
}

/**
 * Request to compute a bill.
 */
data class ComputeBillRequest(
    val billId: String,
    val utilityId: String,
    val customerId: String,
    val billingPeriodId: String,
    val serviceState: String,
)
