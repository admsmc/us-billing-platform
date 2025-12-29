package com.example.usbilling.portal.client

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

@Component
class NotificationClient(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${customer-portal.notification-service-url}")
    private val notificationServiceUrl: String,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val notificationClient: WebClient by lazy {
        webClientBuilder.baseUrl(notificationServiceUrl).build()
    }

    fun sendPaymentConfirmation(
        customerId: String,
        utilityId: String,
        email: String?,
        paymentId: String,
        amount: Double,
        accountId: String,
    ) {
        try {
            val notificationData = mapOf(
                "templateId" to "payment-confirmation",
                "channel" to "EMAIL",
                "recipient" to mapOf(
                    "customerId" to customerId,
                    "email" to email,
                ),
                "data" to mapOf(
                    "paymentId" to paymentId,
                    "amount" to amount,
                    "accountId" to accountId,
                    "utilityId" to utilityId,
                ),
            )

            notificationClient
                .post()
                .uri("/notifications")
                .bodyValue(notificationData)
                .retrieve()
                .bodyToMono<Map<String, Any>>()
                .block()

            log.info("Payment confirmation notification sent for payment: $paymentId")
        } catch (e: Exception) {
            log.error("Failed to send payment confirmation notification for payment: $paymentId", e)
            // Don't throw - notification failures shouldn't break payment flow
        }
    }

    fun sendAutopayEnrollmentConfirmation(
        customerId: String,
        utilityId: String,
        email: String?,
        accountId: String,
        paymentMethodLast4: String?,
    ) {
        try {
            val notificationData = mapOf(
                "templateId" to "autopay-enrollment-confirmation",
                "channel" to "EMAIL",
                "recipient" to mapOf(
                    "customerId" to customerId,
                    "email" to email,
                ),
                "data" to mapOf(
                    "accountId" to accountId,
                    "utilityId" to utilityId,
                    "paymentMethodLast4" to paymentMethodLast4,
                ),
            )

            notificationClient
                .post()
                .uri("/notifications")
                .bodyValue(notificationData)
                .retrieve()
                .bodyToMono<Map<String, Any>>()
                .block()

            log.info("Autopay enrollment confirmation sent for account: $accountId")
        } catch (e: Exception) {
            log.error("Failed to send autopay enrollment confirmation for account: $accountId", e)
        }
    }

    fun sendPaymentFailureAlert(
        customerId: String,
        utilityId: String,
        email: String?,
        paymentId: String,
        amount: Double,
        accountId: String,
        errorMessage: String,
    ) {
        try {
            val notificationData = mapOf(
                "templateId" to "payment-failure-alert",
                "channel" to "EMAIL",
                "recipient" to mapOf(
                    "customerId" to customerId,
                    "email" to email,
                ),
                "data" to mapOf(
                    "paymentId" to paymentId,
                    "amount" to amount,
                    "accountId" to accountId,
                    "utilityId" to utilityId,
                    "errorMessage" to errorMessage,
                ),
            )

            notificationClient
                .post()
                .uri("/notifications")
                .bodyValue(notificationData)
                .retrieve()
                .bodyToMono<Map<String, Any>>()
                .block()

            log.info("Payment failure alert sent for payment: $paymentId")
        } catch (e: Exception) {
            log.error("Failed to send payment failure alert for payment: $paymentId", e)
        }
    }

    fun sendAutopayCancellationConfirmation(
        customerId: String,
        utilityId: String,
        email: String?,
        accountId: String,
    ) {
        try {
            val notificationData = mapOf(
                "templateId" to "autopay-cancellation-confirmation",
                "channel" to "EMAIL",
                "recipient" to mapOf(
                    "customerId" to customerId,
                    "email" to email,
                ),
                "data" to mapOf(
                    "accountId" to accountId,
                    "utilityId" to utilityId,
                ),
            )

            notificationClient
                .post()
                .uri("/notifications")
                .bodyValue(notificationData)
                .retrieve()
                .bodyToMono<Map<String, Any>>()
                .block()

            log.info("Autopay cancellation confirmation sent for account: $accountId")
        } catch (e: Exception) {
            log.error("Failed to send autopay cancellation confirmation for account: $accountId", e)
        }
    }
}
