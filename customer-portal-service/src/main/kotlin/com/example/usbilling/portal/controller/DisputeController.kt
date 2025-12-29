package com.example.usbilling.portal.controller

import com.example.usbilling.portal.security.CustomerPrincipal
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

@RestController
@RequestMapping("/api/customers/me/disputes")
class DisputeController(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${customer-portal.case-management-service-url}")
    private val caseManagementServiceUrl: String,
) {

    private val caseManagementClient: WebClient by lazy {
        webClientBuilder.baseUrl(caseManagementServiceUrl).build()
    }

    /**
     * Submit a new billing dispute.
     */
    @PostMapping
    fun submitDispute(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @RequestBody request: SubmitDisputeRequest,
    ): ResponseEntity<Any> {
        // Validate account access
        if (!principal.hasAccessToAccount(request.accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to account: ${request.accountId}"))
        }

        // Validate request
        if (request.disputeReason.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Dispute reason is required"))
        }

        val disputeData = mapOf(
            "customerId" to principal.customerId,
            "accountId" to request.accountId,
            "billId" to request.billId,
            "disputeType" to request.disputeType,
            "disputeReason" to request.disputeReason,
            "disputedAmountCents" to request.disputedAmountCents,
        )

        val response = caseManagementClient
            .post()
            .uri("/utilities/{utilityId}/disputes", principal.utilityId)
            .header("X-User-Id", "customer:${principal.customerId}")
            .bodyValue(disputeData)
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Dispute submission failed: $body")
                }
            }
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    /**
     * Get customer's disputes.
     */
    @GetMapping
    fun getDisputes(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @RequestParam(defaultValue = "50") limit: Int,
    ): ResponseEntity<Any> {
        val response = caseManagementClient
            .get()
            .uri { builder ->
                builder
                    .path("/utilities/{utilityId}/customers/{customerId}/disputes")
                    .queryParam("limit", limit)
                    .build(principal.utilityId, principal.customerId)
            }
            .retrieve()
            .bodyToMono<List<Map<String, Any>>>()
            .block()

        return ResponseEntity.ok(response)
    }

    /**
     * Get dispute status and details.
     */
    @GetMapping("/{disputeId}")
    fun getDisputeStatus(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable disputeId: String,
    ): ResponseEntity<Any> {
        val response = caseManagementClient
            .get()
            .uri("/utilities/{utilityId}/disputes/{disputeId}", principal.utilityId, disputeId)
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Failed to fetch dispute: $body")
                }
            }
            .bodyToMono<Map<String, Any>>()
            .block()

        // Verify customer has access to this dispute
        val dispute = response?.get("dispute") as? Map<*, *>
        val customerId = dispute?.get("customerId") as? String
        if (customerId != principal.customerId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to dispute: $disputeId"))
        }

        return ResponseEntity.ok(response)
    }
}

data class SubmitDisputeRequest(
    val accountId: String,
    val billId: String?,
    val disputeType: String, // HIGH_BILL, ESTIMATED_BILL, METER_ACCURACY, SERVICE_QUALITY, CHARGE_ERROR
    val disputeReason: String,
    val disputedAmountCents: Long?,
)
