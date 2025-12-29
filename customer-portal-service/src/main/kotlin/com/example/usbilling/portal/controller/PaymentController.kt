package com.example.usbilling.portal.controller

import com.example.usbilling.portal.client.NotificationClient
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.LocalDate

@RestController
@RequestMapping("/api/customers/me/payments")
class PaymentController(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${customer-portal.payments-service-url}")
    private val paymentsServiceUrl: String,
    private val notificationClient: NotificationClient,
) {

    private val paymentsClient: WebClient by lazy {
        webClientBuilder.baseUrl(paymentsServiceUrl).build()
    }

    @PostMapping
    fun submitPayment(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @RequestBody paymentRequest: PaymentRequest,
    ): ResponseEntity<Any> {
        // Validate that the customer has access to the account
        if (!principal.hasAccessToAccount(paymentRequest.accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to account: ${paymentRequest.accountId}"))
        }

        // Validate payment amount
        if (paymentRequest.amount <= 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Payment amount must be greater than zero"))
        }

        // Submit payment to payments-service
        val paymentData = mapOf(
            "customerId" to principal.customerId,
            "utilityId" to principal.utilityId,
            "accountId" to paymentRequest.accountId,
            "amount" to paymentRequest.amount,
            "paymentMethodType" to paymentRequest.paymentMethodType,
            "paymentMethodToken" to paymentRequest.paymentMethodToken,
            "billId" to paymentRequest.billId,
            "scheduledDate" to (paymentRequest.scheduledDate ?: LocalDate.now()).toString(),
        )

        val response = paymentsClient
            .post()
            .uri("/utilities/{utilityId}/payments", principal.utilityId)
            .bodyValue(paymentData)
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Payment submission failed: $body")
                }
            }
            .bodyToMono<Map<String, Any>>()
            .block()

        // Send payment confirmation notification
        val paymentId = response?.get("paymentId") as? String ?: "unknown"
        notificationClient.sendPaymentConfirmation(
            customerId = principal.customerId,
            utilityId = principal.utilityId,
            email = principal.email,
            paymentId = paymentId,
            amount = paymentRequest.amount,
            accountId = paymentRequest.accountId,
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping
    fun getPaymentHistory(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @RequestParam(required = false) accountId: String?,
        @RequestParam(required = false) startDate: LocalDate?,
        @RequestParam(required = false) endDate: LocalDate?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): ResponseEntity<Any> {
        // Validate account access if specified
        if (accountId != null && !principal.hasAccessToAccount(accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to account: $accountId"))
        }

        val response = paymentsClient
            .get()
            .uri { builder ->
                var uriBuilder = builder
                    .path("/utilities/{utilityId}/customers/{customerId}/payments")
                    .queryParam("limit", limit)

                if (accountId != null) {
                    uriBuilder = uriBuilder.queryParam("accountId", accountId)
                }
                if (startDate != null) {
                    uriBuilder = uriBuilder.queryParam("startDate", startDate)
                }
                if (endDate != null) {
                    uriBuilder = uriBuilder.queryParam("endDate", endDate)
                }

                uriBuilder.build(principal.utilityId, principal.customerId)
            }
            .retrieve()
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.ok(response)
    }

    @GetMapping("/{paymentId}")
    fun getPaymentStatus(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable paymentId: String,
    ): ResponseEntity<Any> {
        val response = paymentsClient
            .get()
            .uri("/utilities/{utilityId}/payments/{paymentId}", principal.utilityId, paymentId)
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Failed to fetch payment: $body")
                }
            }
            .bodyToMono<Map<String, Any>>()
            .block()

        // Verify customer has access to this payment's account
        val accountId = response?.get("accountId") as? String
        if (accountId != null && !principal.hasAccessToAccount(accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to payment: $paymentId"))
        }

        return ResponseEntity.ok(response)
    }
}

data class PaymentRequest(
    val accountId: String,
    val amount: Double,
    val paymentMethodType: String,
    val paymentMethodToken: String?,
    val billId: String?,
    val scheduledDate: LocalDate?,
)
