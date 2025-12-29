package com.example.usbilling.portal.controller

import com.example.usbilling.portal.client.NotificationClient
import com.example.usbilling.portal.security.CustomerPrincipal
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

@RestController
@RequestMapping("/api/customers/me/autopay")
class AutopayController(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${customer-portal.payments-service-url}")
    private val paymentsServiceUrl: String,
    private val notificationClient: NotificationClient,
) {

    private val paymentsClient: WebClient by lazy {
        webClientBuilder.baseUrl(paymentsServiceUrl).build()
    }

    @PostMapping("/enroll")
    fun enrollInAutopay(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @RequestBody request: AutopayEnrollmentRequest,
    ): ResponseEntity<Any> {
        // Validate account access
        if (!principal.hasAccessToAccount(request.accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to account: ${request.accountId}"))
        }

        // Validate payment method token
        if (request.paymentMethodToken.isNullOrBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Payment method token is required"))
        }

        val enrollmentData = mapOf(
            "customerId" to principal.customerId,
            "utilityId" to principal.utilityId,
            "accountId" to request.accountId,
            "paymentMethodToken" to request.paymentMethodToken,
            "enrollmentType" to (request.enrollmentType ?: "FULL_BALANCE"),
            "dayBeforeDueDate" to (request.dayBeforeDueDate ?: 5),
        )

        val response = paymentsClient
            .post()
            .uri("/utilities/{utilityId}/accounts/{accountId}/autopay", principal.utilityId, request.accountId)
            .bodyValue(enrollmentData)
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Autopay enrollment failed: $body")
                }
            }
            .bodyToMono<Map<String, Any>>()
            .block()

        // Send autopay enrollment confirmation
        val paymentMethodLast4 = response?.get("paymentMethodLast4") as? String
        notificationClient.sendAutopayEnrollmentConfirmation(
            customerId = principal.customerId,
            utilityId = principal.utilityId,
            email = principal.email,
            accountId = request.accountId,
            paymentMethodLast4 = paymentMethodLast4,
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping("/status")
    fun getAutopayStatus(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @RequestParam accountId: String,
    ): ResponseEntity<Any> {
        // Validate account access
        if (!principal.hasAccessToAccount(accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to account: $accountId"))
        }

        val response = paymentsClient
            .get()
            .uri("/utilities/{utilityId}/accounts/{accountId}/autopay", principal.utilityId, accountId)
            .retrieve()
            .onStatus({ it == HttpStatus.NOT_FOUND }) {
                // Not enrolled - return empty status
                it.bodyToMono<String>().map {
                    RuntimeException("NOT_ENROLLED")
                }
            }
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Failed to fetch autopay status: $body")
                }
            }
            .bodyToMono<Map<String, Any>>()
            .onErrorResume { error ->
                if (error.message == "NOT_ENROLLED") {
                    // Return a not-enrolled response
                    org.reactivestreams.Publisher {
                        it.onNext(
                            mapOf(
                                "enrolled" to false,
                                "accountId" to accountId,
                            ),
                        )
                        it.onComplete()
                    }.let { reactor.core.publisher.Mono.from(it) }
                } else {
                    reactor.core.publisher.Mono.error(error)
                }
            }
            .block()

        return ResponseEntity.ok(response)
    }

    @DeleteMapping("/cancel")
    fun cancelAutopay(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @RequestParam accountId: String,
    ): ResponseEntity<Any> {
        // Validate account access
        if (!principal.hasAccessToAccount(accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to account: $accountId"))
        }

        paymentsClient
            .delete()
            .uri("/utilities/{utilityId}/accounts/{accountId}/autopay", principal.utilityId, accountId)
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Failed to cancel autopay: $body")
                }
            }
            .bodyToMono<Void>()
            .block()

        // Send autopay cancellation confirmation
        notificationClient.sendAutopayCancellationConfirmation(
            customerId = principal.customerId,
            utilityId = principal.utilityId,
            email = principal.email,
            accountId = accountId,
        )

        return ResponseEntity.ok(mapOf("message" to "Autopay enrollment cancelled successfully"))
    }
}

data class AutopayEnrollmentRequest(
    val accountId: String,
    val paymentMethodToken: String?,
    val enrollmentType: String?,
    val dayBeforeDueDate: Int?,
)
