package com.example.usbilling.portal.controller

import com.example.usbilling.portal.security.CustomerPrincipal
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

@RestController
@RequestMapping("/api/customers/me/outages")
class OutageController(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${customer-portal.outage-service-url}")
    private val outageServiceUrl: String,
) {

    private val outageClient: WebClient by lazy {
        webClientBuilder.baseUrl(outageServiceUrl).build()
    }

    @PostMapping("/report")
    fun reportOutage(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @RequestBody report: OutageReport,
    ): ResponseEntity<Any> {
        // Validate account access
        if (!principal.hasAccessToAccount(report.accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to account: ${report.accountId}"))
        }

        val reportData = mapOf(
            "customerId" to principal.customerId,
            "utilityId" to principal.utilityId,
            "accountId" to report.accountId,
            "serviceAddress" to report.serviceAddress,
            "outageType" to report.outageType,
            "description" to report.description,
            "contactPhone" to report.contactPhone,
        )

        val response = outageClient
            .post()
            .uri("/utilities/{utilityId}/outages/report", principal.utilityId)
            .bodyValue(reportData)
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Failed to report outage: $body")
                }
            }
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping
    fun listReportedOutages(
        @AuthenticationPrincipal principal: CustomerPrincipal,
    ): ResponseEntity<Any> {
        val response = outageClient
            .get()
            .uri("/utilities/{utilityId}/customers/{customerId}/outages", principal.utilityId, principal.customerId)
            .retrieve()
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.ok(response)
    }

    @GetMapping("/status")
    fun checkOutageStatus(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @RequestParam address: String?,
        @RequestParam accountId: String?,
    ): ResponseEntity<Any> {
        // Validate account access if specified
        if (accountId != null && !principal.hasAccessToAccount(accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to account: $accountId"))
        }

        val response = outageClient
            .get()
            .uri { builder ->
                var uriBuilder = builder.path("/utilities/{utilityId}/outages/status")

                if (address != null) {
                    uriBuilder = uriBuilder.queryParam("address", address)
                }
                if (accountId != null) {
                    uriBuilder = uriBuilder.queryParam("accountId", accountId)
                }

                uriBuilder.build(principal.utilityId)
            }
            .retrieve()
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.ok(response)
    }
}

data class OutageReport(
    val accountId: String,
    val serviceAddress: String,
    val outageType: String,
    val description: String?,
    val contactPhone: String?,
)
