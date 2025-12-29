package com.example.usbilling.portal.controller

import com.example.usbilling.portal.security.CustomerPrincipal
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

@RestController
@RequestMapping("/api/customers/me/payment-methods")
class PaymentMethodController(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${customer-portal.payments-service-url}")
    private val paymentsServiceUrl: String,
) {

    private val paymentsClient: WebClient by lazy {
        webClientBuilder.baseUrl(paymentsServiceUrl).build()
    }

    @PostMapping
    fun addPaymentMethod(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @RequestBody request: AddPaymentMethodRequest,
    ): ResponseEntity<Any> {
        // Validate payment method type
        val validTypes = setOf("CREDIT_CARD", "DEBIT_CARD", "BANK_ACCOUNT", "ACH")
        if (request.paymentMethodType !in validTypes) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Invalid payment method type. Must be one of: ${validTypes.joinToString()}"))
        }

        val paymentMethodData = mapOf(
            "customerId" to principal.customerId,
            "utilityId" to principal.utilityId,
            "paymentMethodType" to request.paymentMethodType,
            "last4Digits" to request.last4Digits,
            "expirationMonth" to request.expirationMonth,
            "expirationYear" to request.expirationYear,
            "nickname" to request.nickname,
            "billingAddress" to request.billingAddress,
            "isDefault" to (request.isDefault ?: false),
            "tokenizedData" to request.tokenizedData,
        )

        val response = paymentsClient
            .post()
            .uri("/utilities/{utilityId}/customers/{customerId}/payment-methods", principal.utilityId, principal.customerId)
            .bodyValue(paymentMethodData)
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Failed to add payment method: $body")
                }
            }
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping
    fun listPaymentMethods(
        @AuthenticationPrincipal principal: CustomerPrincipal,
    ): ResponseEntity<Any> {
        val response = paymentsClient
            .get()
            .uri("/utilities/{utilityId}/customers/{customerId}/payment-methods", principal.utilityId, principal.customerId)
            .retrieve()
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.ok(response)
    }

    @DeleteMapping("/{paymentMethodId}")
    fun removePaymentMethod(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable paymentMethodId: String,
    ): ResponseEntity<Any> {
        paymentsClient
            .delete()
            .uri(
                "/utilities/{utilityId}/customers/{customerId}/payment-methods/{paymentMethodId}",
                principal.utilityId,
                principal.customerId,
                paymentMethodId,
            )
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Failed to remove payment method: $body")
                }
            }
            .bodyToMono<Void>()
            .block()

        return ResponseEntity.ok(mapOf("message" to "Payment method removed successfully"))
    }

    @PutMapping("/{paymentMethodId}/default")
    fun setDefaultPaymentMethod(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable paymentMethodId: String,
    ): ResponseEntity<Any> {
        val response = paymentsClient
            .put()
            .uri(
                "/utilities/{utilityId}/customers/{customerId}/payment-methods/{paymentMethodId}/default",
                principal.utilityId,
                principal.customerId,
                paymentMethodId,
            )
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Failed to set default payment method: $body")
                }
            }
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.ok(response)
    }
}

data class AddPaymentMethodRequest(
    val paymentMethodType: String,
    val last4Digits: String?,
    val expirationMonth: Int?,
    val expirationYear: Int?,
    val nickname: String?,
    val billingAddress: Map<String, String>?,
    val isDefault: Boolean?,
    val tokenizedData: String,
)
