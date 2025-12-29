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
import java.time.LocalTime

@RestController
@RequestMapping("/api/customers/me/field-service-requests")
class FieldServiceRequestController(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${customer-portal.customer-service-url}")
    private val customerServiceUrl: String,
) {

    private val customerClient: WebClient by lazy {
        webClientBuilder.baseUrl(customerServiceUrl).build()
    }

    @PostMapping
    fun submitFieldServiceRequest(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @RequestBody request: FieldServiceRequestSubmission,
    ): ResponseEntity<Any> {
        // Validate account access
        if (!principal.hasAccessToAccount(request.accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to account: ${request.accountId}"))
        }

        // Validate request type
        val validTypes = setOf(
            "START_SERVICE",
            "STOP_SERVICE",
            "TRANSFER_SERVICE",
            "MOVE_SERVICE",
            "METER_TEST",
            "RECONNECT",
        )
        if (request.requestType !in validTypes) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Invalid request type. Must be one of: ${validTypes.joinToString()}"))
        }

        val requestData = mapOf(
            "utilityId" to principal.utilityId,
            "customerId" to principal.customerId,
            "accountId" to request.accountId,
            "requestType" to request.requestType,
            "serviceType" to request.serviceType,
            "serviceAddress" to request.serviceAddress,
            "requestedDate" to request.requestedDate?.toString(),
            "notes" to request.notes,
        )

        val response = customerClient
            .post()
            .uri("/utilities/{utilityId}/service-requests", principal.utilityId)
            .bodyValue(requestData)
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Field service request submission failed: $body")
                }
            }
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping
    fun getFieldServiceRequests(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @RequestParam(defaultValue = "50") limit: Int,
    ): ResponseEntity<Any> {
        val response = customerClient
            .get()
            .uri(
                "/utilities/{utilityId}/customers/{customerId}/service-requests?limit={limit}",
                principal.utilityId,
                principal.customerId,
                limit,
            )
            .retrieve()
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.ok(response)
    }

    @GetMapping("/{requestId}")
    fun getFieldServiceRequestDetail(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable requestId: String,
    ): ResponseEntity<Any> {
        val response = customerClient
            .get()
            .uri(
                "/utilities/{utilityId}/service-requests/{requestId}",
                principal.utilityId,
                requestId,
            )
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Failed to fetch field service request: $body")
                }
            }
            .bodyToMono<Map<String, Any>>()
            .block()

        // Verify customer has access to this request
        val customerId = response?.get("customerId") as? String
        if (customerId != principal.customerId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to field service request: $requestId"))
        }

        return ResponseEntity.ok(response)
    }

    @PutMapping("/{requestId}/cancel")
    fun cancelFieldServiceRequest(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable requestId: String,
    ): ResponseEntity<Any> {
        // First verify the customer owns this request
        val existing = customerClient
            .get()
            .uri(
                "/utilities/{utilityId}/service-requests/{requestId}",
                principal.utilityId,
                requestId,
            )
            .retrieve()
            .bodyToMono<Map<String, Any>>()
            .block()

        val customerId = existing?.get("customerId") as? String
        if (customerId != principal.customerId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to field service request: $requestId"))
        }

        val response = customerClient
            .put()
            .uri(
                "/utilities/{utilityId}/service-requests/{requestId}/cancel",
                principal.utilityId,
                requestId,
            )
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Failed to cancel field service request: $body")
                }
            }
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.ok(response)
    }

    @PostMapping("/{requestId}/reschedule")
    fun rescheduleAppointment(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable requestId: String,
        @RequestBody rescheduleRequest: RescheduleAppointmentRequest,
    ): ResponseEntity<Any> {
        // First verify the customer owns this request
        val existing = customerClient
            .get()
            .uri(
                "/utilities/{utilityId}/service-requests/{requestId}",
                principal.utilityId,
                requestId,
            )
            .retrieve()
            .bodyToMono<Map<String, Any>>()
            .block()

        val customerId = existing?.get("customerId") as? String
        if (customerId != principal.customerId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to field service request: $requestId"))
        }

        val requestData = mapOf(
            "scheduledDate" to rescheduleRequest.scheduledDate.toString(),
            "timeWindow" to rescheduleRequest.timeWindow,
            "startTime" to rescheduleRequest.startTime?.toString(),
            "endTime" to rescheduleRequest.endTime?.toString(),
        )

        val response = customerClient
            .post()
            .uri(
                "/utilities/{utilityId}/service-requests/{requestId}/reschedule",
                principal.utilityId,
                requestId,
            )
            .bodyValue(requestData)
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Failed to reschedule appointment: $body")
                }
            }
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.ok(response)
    }
}

data class FieldServiceRequestSubmission(
    val accountId: String,
    val requestType: String,
    val serviceType: String,
    val serviceAddress: String,
    val requestedDate: LocalDate?,
    val notes: String?,
)

data class RescheduleAppointmentRequest(
    val scheduledDate: LocalDate,
    val timeWindow: String,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
)
