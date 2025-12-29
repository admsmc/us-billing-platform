package com.example.usbilling.portal.controller

import com.example.usbilling.portal.security.CustomerPrincipal
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.LocalDate

@RestController
@RequestMapping("/api/customers/me/payment-plans")
class PaymentPlanController(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${customer-portal.customer-service-url}")
    private val customerServiceUrl: String,
) {

    private val customerServiceClient: WebClient by lazy {
        webClientBuilder.baseUrl(customerServiceUrl).build()
    }

    /**
     * Check payment plan eligibility for the current customer.
     */
    @GetMapping("/eligibility")
    fun checkEligibility(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @RequestParam totalAmountCents: Long,
    ): ResponseEntity<Any> {
        if (totalAmountCents <= 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Amount must be positive"))
        }

        val response = customerServiceClient
            .get()
            .uri { builder ->
                builder
                    .path("/utilities/{utilityId}/payment-plans/customers/{customerId}/eligibility")
                    .queryParam("totalAmountCents", totalAmountCents)
                    .build(principal.utilityId, principal.customerId)
            }
            .retrieve()
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.ok(response)
    }

    /**
     * Get customer's payment plans.
     */
    @GetMapping
    fun getPaymentPlans(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @RequestParam(required = false) status: String?,
    ): ResponseEntity<Any> {
        val response = customerServiceClient
            .get()
            .uri { builder ->
                var uriBuilder = builder
                    .path("/utilities/{utilityId}/payment-plans/customers/{customerId}")

                if (status != null) {
                    uriBuilder = uriBuilder.queryParam("status", status)
                }

                uriBuilder.build(principal.utilityId, principal.customerId)
            }
            .retrieve()
            .bodyToMono<List<Map<String, Any>>>()
            .block()

        return ResponseEntity.ok(response)
    }

    /**
     * Get a specific payment plan with installments.
     */
    @GetMapping("/{planId}")
    fun getPaymentPlan(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable planId: String,
    ): ResponseEntity<Any> {
        val response = customerServiceClient
            .get()
            .uri("/utilities/{utilityId}/payment-plans/{planId}", principal.utilityId, planId)
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Failed to fetch payment plan: $body")
                }
            }
            .bodyToMono<Map<String, Any>>()
            .block()

        // Verify customer has access to this payment plan
        val customerId = (response?.get("plan") as? Map<*, *>)?.get("customerId") as? String
        if (customerId != principal.customerId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to payment plan: $planId"))
        }

        return ResponseEntity.ok(response)
    }

    /**
     * Request a payment plan (customer-initiated).
     */
    @PostMapping
    fun requestPaymentPlan(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @RequestBody request: PaymentPlanRequest,
    ): ResponseEntity<Any> {
        // Validate that the customer has access to the account
        if (!principal.hasAccessToAccount(request.accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to account: ${request.accountId}"))
        }

        // Validate request
        if (request.totalAmountCents <= 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Total amount must be positive"))
        }
        if (request.downPaymentCents < 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Down payment cannot be negative"))
        }
        if (request.installmentCount <= 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Installment count must be positive"))
        }

        val requestData = mapOf(
            "customerId" to principal.customerId,
            "accountId" to request.accountId,
            "planType" to request.planType,
            "totalAmountCents" to request.totalAmountCents,
            "downPaymentCents" to request.downPaymentCents,
            "installmentCount" to request.installmentCount,
            "paymentFrequency" to request.paymentFrequency,
            "startDate" to (request.startDate ?: LocalDate.now()).toString(),
            "maxMissedPayments" to (request.maxMissedPayments ?: 2),
        )

        val response = customerServiceClient
            .post()
            .uri("/utilities/{utilityId}/payment-plans", principal.utilityId)
            .header("X-User-Id", "customer:${principal.customerId}")
            .bodyValue(requestData)
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Payment plan creation failed: $body")
                }
            }
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    /**
     * Request cancellation of a payment plan (customer-initiated).
     */
    @PostMapping("/{planId}/cancel-request")
    fun requestCancellation(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable planId: String,
        @RequestBody request: CancelRequestBody,
    ): ResponseEntity<Any> {
        // Verify ownership
        val planResponse = customerServiceClient
            .get()
            .uri("/utilities/{utilityId}/payment-plans/{planId}", principal.utilityId, planId)
            .retrieve()
            .bodyToMono<Map<String, Any>>()
            .block()

        val plan = planResponse?.get("plan") as? Map<*, *>
        val customerId = plan?.get("customerId") as? String
        if (customerId != principal.customerId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to payment plan: $planId"))
        }

        // For customer-initiated cancellations, we just mark it with a customer request reason
        // CSRs would review and actually cancel via the internal API
        val cancelData = mapOf(
            "reason" to "Customer requested cancellation: ${request.reason}",
        )

        val response = customerServiceClient
            .post()
            .uri("/utilities/{utilityId}/payment-plans/{planId}/cancel", principal.utilityId, planId)
            .header("X-User-Id", "customer:${principal.customerId}")
            .bodyValue(cancelData)
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Payment plan cancellation failed: $body")
                }
            }
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.ok(response)
    }
}

data class PaymentPlanRequest(
    val accountId: String,
    val planType: String,
    val totalAmountCents: Long,
    val downPaymentCents: Long,
    val installmentCount: Int,
    val paymentFrequency: String,
    val startDate: LocalDate?,
    val maxMissedPayments: Int?,
)

data class CancelRequestBody(
    val reason: String,
)
