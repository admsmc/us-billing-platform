package com.example.usbilling.portal.controller

import com.example.usbilling.portal.security.CustomerPrincipal
import com.example.usbilling.portal.service.UsageChartService
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
@RequestMapping("/api/customers/me/accounts/{accountId}/usage")
class UsageController(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${customer-portal.customer-service-url}")
    private val customerServiceUrl: String,
    private val usageChartService: UsageChartService,
) {

    private val customerClient: WebClient by lazy {
        webClientBuilder.baseUrl(customerServiceUrl).build()
    }

    @GetMapping
    fun getUsageData(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable accountId: String,
        @RequestParam(required = false) startDate: LocalDate?,
        @RequestParam(required = false) endDate: LocalDate?,
        @RequestParam(defaultValue = "DAILY") granularity: String,
    ): ResponseEntity<Any> {
        // Validate account access
        if (!principal.hasAccessToAccount(accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to account: $accountId"))
        }

        // Fetch usage data from customer-service (or meter-reading-service)
        val response = customerClient
            .get()
            .uri { builder ->
                var uriBuilder = builder
                    .path("/utilities/{utilityId}/accounts/{accountId}/usage")
                    .queryParam("granularity", granularity)

                if (startDate != null) {
                    uriBuilder = uriBuilder.queryParam("startDate", startDate)
                }
                if (endDate != null) {
                    uriBuilder = uriBuilder.queryParam("endDate", endDate)
                }

                uriBuilder.build(principal.utilityId, accountId)
            }
            .retrieve()
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.ok(response)
    }

    @GetMapping("/comparison")
    fun getUsageComparison(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable accountId: String,
        @RequestParam(required = false) currentStart: LocalDate?,
        @RequestParam(required = false) currentEnd: LocalDate?,
        @RequestParam(defaultValue = "PREVIOUS_PERIOD") comparisonType: String,
    ): ResponseEntity<Any> {
        // Validate account access
        if (!principal.hasAccessToAccount(accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to account: $accountId"))
        }

        val response = customerClient
            .get()
            .uri { builder ->
                var uriBuilder = builder
                    .path("/utilities/{utilityId}/accounts/{accountId}/usage/comparison")
                    .queryParam("comparisonType", comparisonType)

                if (currentStart != null) {
                    uriBuilder = uriBuilder.queryParam("currentStart", currentStart)
                }
                if (currentEnd != null) {
                    uriBuilder = uriBuilder.queryParam("currentEnd", currentEnd)
                }

                uriBuilder.build(principal.utilityId, accountId)
            }
            .retrieve()
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.ok(response)
    }

    @GetMapping("/projections")
    fun getUsageProjections(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable accountId: String,
        @RequestParam(defaultValue = "30") projectionDays: Int,
    ): ResponseEntity<Any> {
        // Validate account access
        if (!principal.hasAccessToAccount(accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to account: $accountId"))
        }

        // Validate projection days
        if (projectionDays < 1 || projectionDays > 365) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Projection days must be between 1 and 365"))
        }

        val response = customerClient
            .get()
            .uri(
                "/utilities/{utilityId}/accounts/{accountId}/usage/projections?days={days}",
                principal.utilityId,
                accountId,
                projectionDays,
            )
            .retrieve()
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.ok(response)
    }

    @GetMapping("/chart/timeseries")
    fun getTimeSeriesChartData(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable accountId: String,
        @RequestParam(required = false) startDate: LocalDate?,
        @RequestParam(required = false) endDate: LocalDate?,
        @RequestParam(defaultValue = "DAILY") granularity: String,
    ): ResponseEntity<Any> {
        // Validate account access
        if (!principal.hasAccessToAccount(accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to account: $accountId"))
        }

        val chartData = usageChartService.generateTimeSeriesData(
            utilityId = principal.utilityId,
            accountId = accountId,
            startDate = startDate,
            endDate = endDate,
            granularity = granularity,
        )

        return ResponseEntity.ok(chartData)
    }

    @GetMapping("/chart/breakdown")
    fun getUsageBreakdownChartData(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable accountId: String,
        @RequestParam(required = false) startDate: LocalDate?,
        @RequestParam(required = false) endDate: LocalDate?,
    ): ResponseEntity<Any> {
        // Validate account access
        if (!principal.hasAccessToAccount(accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to account: $accountId"))
        }

        val chartData = usageChartService.generateBreakdownData(
            utilityId = principal.utilityId,
            accountId = accountId,
            startDate = startDate,
            endDate = endDate,
        )

        return ResponseEntity.ok(chartData)
    }

    @GetMapping("/chart/peak-times")
    fun getPeakUsageTimesChartData(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable accountId: String,
        @RequestParam(required = false) date: LocalDate?,
    ): ResponseEntity<Any> {
        // Validate account access
        if (!principal.hasAccessToAccount(accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Access denied to account: $accountId"))
        }

        val chartData = usageChartService.generatePeakTimesData(
            utilityId = principal.utilityId,
            accountId = accountId,
            date = date ?: LocalDate.now(),
        )

        return ResponseEntity.ok(chartData)
    }
}
