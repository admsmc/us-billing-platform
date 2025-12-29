package com.example.usbilling.portal.controller

import com.example.usbilling.portal.security.CustomerPrincipal
import com.example.usbilling.portal.service.InsightGenerator
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/customers/me/accounts/{accountId}/insights")
class InsightsController(
    private val insightGenerator: InsightGenerator,
) {

    @GetMapping
    fun getInsights(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable accountId: String,
    ): ResponseEntity<Any> {
        // Validate account access
        if (!principal.hasAccessToAccount(accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to account: $accountId"))
        }

        val insights = insightGenerator.generateInsights(
            utilityId = principal.utilityId,
            accountId = accountId,
        )

        return ResponseEntity.ok(insights)
    }

    @GetMapping("/tips")
    fun getConservationTips(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable accountId: String,
    ): ResponseEntity<Any> {
        // Validate account access
        if (!principal.hasAccessToAccount(accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to account: $accountId"))
        }

        val tips = insightGenerator.generateConservationTips(
            utilityId = principal.utilityId,
            accountId = accountId,
        )

        return ResponseEntity.ok(mapOf("tips" to tips))
    }

    @GetMapping("/benchmarks")
    fun getBenchmarks(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable accountId: String,
    ): ResponseEntity<Any> {
        // Validate account access
        if (!principal.hasAccessToAccount(accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to account: $accountId"))
        }

        val benchmarks = insightGenerator.generateBenchmarks(
            utilityId = principal.utilityId,
            accountId = accountId,
        )

        return ResponseEntity.ok(benchmarks)
    }
}
