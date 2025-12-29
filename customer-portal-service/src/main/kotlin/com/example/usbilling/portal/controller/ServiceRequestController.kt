package com.example.usbilling.portal.controller

import com.example.usbilling.portal.security.CustomerPrincipal
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

@RestController
@RequestMapping("/api/customers/me/service-requests")
class ServiceRequestController(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${customer-portal.case-management-url}")
    private val caseManagementUrl: String,
) {

    private val caseClient: WebClient by lazy {
        webClientBuilder.baseUrl(caseManagementUrl).build()
    }

    @PostMapping
    fun submitServiceRequest(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @RequestBody request: ServiceRequestSubmission,
    ): ResponseEntity<Any> {
        // Validate account access if specified
        if (request.accountId != null && !principal.hasAccessToAccount(request.accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to account: ${request.accountId}"))
        }

        // Validate request type
        val validTypes = setOf(
            "START_SERVICE",
            "STOP_SERVICE",
            "BILLING_INQUIRY",
            "SERVICE_ISSUE",
            "METER_READING_DISPUTE",
            "PAYMENT_ARRANGEMENT",
            "ACCOUNT_UPDATE",
            "OTHER",
        )
        if (request.requestType !in validTypes) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Invalid request type. Must be one of: ${validTypes.joinToString()}"))
        }

        val requestData = mapOf(
            "customerId" to principal.customerId,
            "utilityId" to principal.utilityId,
            "accountId" to request.accountId,
            "requestType" to request.requestType,
            "subject" to request.subject,
            "description" to request.description,
            "priority" to (request.priority ?: "NORMAL"),
            "contactMethod" to (request.contactMethod ?: "EMAIL"),
            "contactValue" to (request.contactValue ?: principal.email),
        )

        val response = caseClient
            .post()
            .uri("/utilities/{utilityId}/service-requests", principal.utilityId)
            .bodyValue(requestData)
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Failed to submit service request: $body")
                }
            }
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping
    fun listServiceRequests(
        @AuthenticationPrincipal principal: CustomerPrincipal,
    ): ResponseEntity<Any> {
        val response = caseClient
            .get()
            .uri("/utilities/{utilityId}/customers/{customerId}/service-requests", principal.utilityId, principal.customerId)
            .retrieve()
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.ok(response)
    }

    @GetMapping("/{requestId}")
    fun getServiceRequestDetails(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable requestId: String,
    ): ResponseEntity<Any> {
        val response = caseClient
            .get()
            .uri("/utilities/{utilityId}/service-requests/{requestId}", principal.utilityId, requestId)
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Failed to fetch service request: $body")
                }
            }
            .bodyToMono<Map<String, Any>>()
            .block()

        // Verify customer owns this request
        val customerId = response?.get("customerId") as? String
        if (customerId != principal.customerId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to service request: $requestId"))
        }

        return ResponseEntity.ok(response)
    }

    @PostMapping("/{requestId}/messages")
    fun addMessageToServiceRequest(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable requestId: String,
        @RequestBody message: ServiceRequestMessage,
    ): ResponseEntity<Any> {
        // Validate message content
        if (message.content.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Message content cannot be empty"))
        }

        val messageData = mapOf(
            "customerId" to principal.customerId,
            "utilityId" to principal.utilityId,
            "content" to message.content,
            "senderType" to "CUSTOMER",
        )

        val response = caseClient
            .post()
            .uri("/utilities/{utilityId}/service-requests/{requestId}/messages", principal.utilityId, requestId)
            .bodyValue(messageData)
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Failed to add message: $body")
                }
            }
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }
}

data class ServiceRequestSubmission(
    val accountId: String?,
    val requestType: String,
    val subject: String,
    val description: String,
    val priority: String?,
    val contactMethod: String?,
    val contactValue: String?,
)

data class ServiceRequestMessage(
    val content: String,
)
