package com.example.usbilling.portal.controller

import com.example.usbilling.portal.security.CustomerPrincipal
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.LocalDate

@RestController
@RequestMapping("/api/customers/me/bills")
class BillController(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${customer-portal.billing-orchestrator-url}")
    private val billingOrchestratorUrl: String,
) {

    private val orchestratorClient: WebClient by lazy {
        webClientBuilder.baseUrl(billingOrchestratorUrl).build()
    }

    @GetMapping
    fun listBills(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @RequestParam(required = false) accountId: String?,
        @RequestParam(required = false) startDate: LocalDate?,
        @RequestParam(required = false) endDate: LocalDate?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): ResponseEntity<Any> {
        // Validate account access if accountId is specified
        if (accountId != null && !principal.hasAccessToAccount(accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to account: $accountId"))
        }

        val response = orchestratorClient
            .get()
            .uri { builder ->
                var uriBuilder = builder
                    .path("/utilities/{utilityId}/bills")
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

                uriBuilder.build(principal.utilityId)
            }
            .retrieve()
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.ok(response)
    }

    @GetMapping("/{billId}")
    fun getBillDetail(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable billId: String,
    ): ResponseEntity<Any> {
        val response = orchestratorClient
            .get()
            .uri("/utilities/{utilityId}/bills/{billId}", principal.utilityId, billId)
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Failed to fetch bill: $body")
                }
            }
            .bodyToMono<Map<String, Any>>()
            .block()

        // Verify customer has access to this bill's account
        val accountId = response?.get("accountId") as? String
        if (accountId != null && !principal.hasAccessToAccount(accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to bill: $billId"))
        }

        return ResponseEntity.ok(response)
    }

    @GetMapping("/{billId}/pdf")
    fun getBillPdf(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable billId: String,
    ): ResponseEntity<ByteArray> {
        // First verify access to the bill
        val bill = orchestratorClient
            .get()
            .uri("/utilities/{utilityId}/bills/{billId}", principal.utilityId, billId)
            .retrieve()
            .bodyToMono<Map<String, Any>>()
            .block()

        val accountId = bill?.get("accountId") as? String
        if (accountId != null && !principal.hasAccessToAccount(accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        // TODO: Implement PDF generation via document-service
        // For now, return a placeholder response
        val placeholder = "PDF generation not yet implemented for bill: $billId".toByteArray()
        return ResponseEntity.ok()
            .header("Content-Type", "application/pdf")
            .header("Content-Disposition", "attachment; filename=bill-$billId.pdf")
            .body(placeholder)
    }

    @GetMapping("/summary")
    fun getBillSummary(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @RequestParam(required = false) accountId: String?,
    ): ResponseEntity<Any> {
        // Validate account access if accountId is specified
        if (accountId != null && !principal.hasAccessToAccount(accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to account: $accountId"))
        }

        // Fetch recent bills to compute summary
        val response = orchestratorClient
            .get()
            .uri { builder ->
                var uriBuilder = builder
                    .path("/utilities/{utilityId}/bills")
                    .queryParam("limit", 12)

                if (accountId != null) {
                    uriBuilder = uriBuilder.queryParam("accountId", accountId)
                }

                uriBuilder.build(principal.utilityId)
            }
            .retrieve()
            .bodyToMono<Map<String, Any>>()
            .block()

        // Extract bills list and compute summary
        val bills = (response?.get("bills") as? List<Map<String, Any>>) ?: emptyList()

        val summary = mapOf(
            "totalBills" to bills.size,
            "totalAmount" to bills.sumOf {
                (it["totalAmount"] as? Number)?.toDouble() ?: 0.0
            },
            "latestBill" to bills.firstOrNull(),
        )

        return ResponseEntity.ok(summary)
    }
}
