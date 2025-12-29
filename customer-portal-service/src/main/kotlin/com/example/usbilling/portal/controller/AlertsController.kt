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
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

@RestController
@RequestMapping("/api/customers/me/alerts")
class AlertsController(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${customer-portal.customer-service-url}")
    private val customerServiceUrl: String,
) {

    private val customerClient: WebClient by lazy {
        webClientBuilder.baseUrl(customerServiceUrl).build()
    }

    @GetMapping
    fun getActiveAlerts(
        @AuthenticationPrincipal principal: CustomerPrincipal,
    ): ResponseEntity<Any> {
        val response = customerClient
            .get()
            .uri("/utilities/{utilityId}/customers/{customerId}/alerts", principal.utilityId, principal.customerId)
            .retrieve()
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.ok(response)
    }

    @PostMapping("/preferences")
    fun configureAlertPreferences(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @RequestBody request: AlertPreferencesRequest,
    ): ResponseEntity<Any> {
        // Validate thresholds
        if (request.highUsageThreshold != null && request.highUsageThreshold <= 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "High usage threshold must be greater than 0"))
        }

        val preferencesData = mapOf(
            "customerId" to principal.customerId,
            "utilityId" to principal.utilityId,
            "highUsageEnabled" to (request.highUsageEnabled ?: true),
            "highUsageThreshold" to request.highUsageThreshold,
            "paymentReminderEnabled" to (request.paymentReminderEnabled ?: true),
            "paymentReminderDaysBefore" to (request.paymentReminderDaysBefore ?: 7),
            "outageNotificationsEnabled" to (request.outageNotificationsEnabled ?: true),
            "billReadyNotificationEnabled" to (request.billReadyNotificationEnabled ?: true),
            "lowBalanceEnabled" to (request.lowBalanceEnabled ?: false),
            "lowBalanceThreshold" to request.lowBalanceThreshold,
        )

        val response = customerClient
            .post()
            .uri("/utilities/{utilityId}/customers/{customerId}/alert-preferences", principal.utilityId, principal.customerId)
            .bodyValue(preferencesData)
            .retrieve()
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.ok(response)
    }

    @GetMapping("/preferences")
    fun getAlertPreferences(
        @AuthenticationPrincipal principal: CustomerPrincipal,
    ): ResponseEntity<Any> {
        val response = customerClient
            .get()
            .uri("/utilities/{utilityId}/customers/{customerId}/alert-preferences", principal.utilityId, principal.customerId)
            .retrieve()
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.ok(response)
    }

    @DeleteMapping("/{alertId}")
    fun dismissAlert(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable alertId: String,
    ): ResponseEntity<Any> {
        customerClient
            .delete()
            .uri(
                "/utilities/{utilityId}/customers/{customerId}/alerts/{alertId}",
                principal.utilityId,
                principal.customerId,
                alertId,
            )
            .retrieve()
            .bodyToMono<Void>()
            .block()

        return ResponseEntity.ok(mapOf("message" to "Alert dismissed successfully"))
    }
}

data class AlertPreferencesRequest(
    val highUsageEnabled: Boolean?,
    val highUsageThreshold: Double?,
    val paymentReminderEnabled: Boolean?,
    val paymentReminderDaysBefore: Int?,
    val outageNotificationsEnabled: Boolean?,
    val billReadyNotificationEnabled: Boolean?,
    val lowBalanceEnabled: Boolean?,
    val lowBalanceThreshold: Double?,
)
