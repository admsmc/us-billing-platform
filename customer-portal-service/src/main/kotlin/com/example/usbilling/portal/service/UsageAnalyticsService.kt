package com.example.usbilling.portal.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.LocalDate
import java.time.YearMonth

@Service
class UsageAnalyticsService(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${customer-portal.customer-service-url}")
    private val customerServiceUrl: String,
) {

    private val customerServiceClient: WebClient by lazy {
        webClientBuilder.baseUrl(customerServiceUrl).build()
    }

    /**
     * Get current billing period usage.
     */
    fun getCurrentUsage(utilityId: String, accountId: String): CurrentUsageData {
        // Query customer-service for current billing period
        val response = customerServiceClient
            .get()
            .uri("/utilities/{utilityId}/accounts/{accountId}/usage/current", utilityId, accountId)
            .retrieve()
            .bodyToMono<Map<String, Any>>()
            .block()

        return CurrentUsageData(
            accountId = accountId,
            periodStart = LocalDate.parse(response?.get("periodStart") as String),
            periodEnd = LocalDate.parse(response?.get("periodEnd") as String),
            daysInPeriod = (response["daysInPeriod"] as Number).toInt(),
            daysElapsed = (response["daysElapsed"] as Number).toInt(),
            currentUsageKwh = (response["currentUsageKwh"] as Number).toDouble(),
            estimatedTotalUsage = (response["estimatedTotalUsage"] as Number?)?.toDouble(),
        )
    }

    /**
     * Get 12-month usage history.
     */
    fun getUsageHistory(utilityId: String, accountId: String, months: Int = 12): List<MonthlyUsageData> {
        val response = customerServiceClient
            .get()
            .uri { builder ->
                builder
                    .path("/utilities/{utilityId}/accounts/{accountId}/usage/history")
                    .queryParam("months", months)
                    .build(utilityId, accountId)
            }
            .retrieve()
            .bodyToMono<List<Map<String, Any>>>()
            .block()

        return response?.map { data ->
            MonthlyUsageData(
                yearMonth = YearMonth.parse(data["yearMonth"] as String),
                usageKwh = (data["usageKwh"] as Number).toDouble(),
                costCents = (data["costCents"] as Number).toLong(),
                daysInPeriod = (data["daysInPeriod"] as Number).toInt(),
            )
        } ?: emptyList()
    }

    /**
     * Compare usage to previous period.
     */
    fun compareUsage(utilityId: String, accountId: String): UsageComparison {
        val current = getCurrentUsage(utilityId, accountId)
        val history = getUsageHistory(utilityId, accountId, 13) // Get 13 months for comparison

        val lastMonth = history.getOrNull(0)
        val lastYear = history.getOrNull(12)

        val monthOverMonthChange = if (lastMonth != null && current.estimatedTotalUsage != null) {
            ((current.estimatedTotalUsage - lastMonth.usageKwh) / lastMonth.usageKwh) * 100
        } else {
            null
        }

        val yearOverYearChange = if (lastYear != null && current.estimatedTotalUsage != null) {
            ((current.estimatedTotalUsage - lastYear.usageKwh) / lastYear.usageKwh) * 100
        } else {
            null
        }

        return UsageComparison(
            currentUsageKwh = current.currentUsageKwh,
            estimatedTotalUsageKwh = current.estimatedTotalUsage,
            lastMonthUsageKwh = lastMonth?.usageKwh,
            lastYearUsageKwh = lastYear?.usageKwh,
            monthOverMonthChangePercent = monthOverMonthChange,
            yearOverYearChangePercent = yearOverYearChange,
        )
    }

    /**
     * Calculate projected bill for current period.
     */
    fun calculateProjectedBill(utilityId: String, accountId: String): ProjectedBill {
        val current = getCurrentUsage(utilityId, accountId)

        // Simple projection based on days elapsed
        val projectedUsageKwh = if (current.daysElapsed > 0) {
            (current.currentUsageKwh / current.daysElapsed) * current.daysInPeriod
        } else {
            current.currentUsageKwh
        }

        // Estimate cost (simplified - would call billing service in production)
        val averageRatePerKwh = 0.12 // $0.12/kWh placeholder
        val projectedCostCents = (projectedUsageKwh * averageRatePerKwh * 100).toLong()

        return ProjectedBill(
            accountId = accountId,
            periodStart = current.periodStart,
            periodEnd = current.periodEnd,
            daysElapsed = current.daysElapsed,
            daysRemaining = current.daysInPeriod - current.daysElapsed,
            currentUsageKwh = current.currentUsageKwh,
            projectedUsageKwh = projectedUsageKwh,
            projectedCostCents = projectedCostCents,
            confidenceLevel = if (current.daysElapsed >= 7) "HIGH" else "LOW",
        )
    }

    /**
     * Analyze usage trends.
     */
    fun analyzeTrends(utilityId: String, accountId: String): UsageTrends {
        val history = getUsageHistory(utilityId, accountId, 12)

        if (history.size < 3) {
            return UsageTrends(
                trend = "INSUFFICIENT_DATA",
                averageMonthlyUsageKwh = null,
                highestUsageMonth = null,
                lowestUsageMonth = null,
                volatilityScore = null,
            )
        }

        val averageUsage = history.map { it.usageKwh }.average()
        val maxUsage = history.maxByOrNull { it.usageKwh }
        val minUsage = history.minByOrNull { it.usageKwh }

        // Calculate simple trend (increasing/decreasing/stable)
        val recentAvg = history.take(3).map { it.usageKwh }.average()
        val olderAvg = history.takeLast(3).map { it.usageKwh }.average()
        val trend = when {
            recentAvg > olderAvg * 1.1 -> "INCREASING"
            recentAvg < olderAvg * 0.9 -> "DECREASING"
            else -> "STABLE"
        }

        // Volatility score (coefficient of variation)
        val stdDev = calculateStdDev(history.map { it.usageKwh })
        val volatility = if (averageUsage > 0) (stdDev / averageUsage) * 100 else 0.0

        return UsageTrends(
            trend = trend,
            averageMonthlyUsageKwh = averageUsage,
            highestUsageMonth = maxUsage?.yearMonth,
            lowestUsageMonth = minUsage?.yearMonth,
            volatilityScore = volatility,
        )
    }

    /**
     * Get usage data for charting.
     */
    fun getChartData(utilityId: String, accountId: String, months: Int = 12): UsageChartData {
        val history = getUsageHistory(utilityId, accountId, months)

        return UsageChartData(
            dataPoints = history.map { data ->
                ChartDataPoint(
                    month = data.yearMonth.toString(),
                    usageKwh = data.usageKwh,
                    costCents = data.costCents,
                )
            },
        )
    }

    private fun calculateStdDev(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return kotlin.math.sqrt(variance)
    }
}

// Data classes

data class CurrentUsageData(
    val accountId: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val daysInPeriod: Int,
    val daysElapsed: Int,
    val currentUsageKwh: Double,
    val estimatedTotalUsage: Double?,
)

data class MonthlyUsageData(
    val yearMonth: YearMonth,
    val usageKwh: Double,
    val costCents: Long,
    val daysInPeriod: Int,
)

data class UsageComparison(
    val currentUsageKwh: Double,
    val estimatedTotalUsageKwh: Double?,
    val lastMonthUsageKwh: Double?,
    val lastYearUsageKwh: Double?,
    val monthOverMonthChangePercent: Double?,
    val yearOverYearChangePercent: Double?,
)

data class ProjectedBill(
    val accountId: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val daysElapsed: Int,
    val daysRemaining: Int,
    val currentUsageKwh: Double,
    val projectedUsageKwh: Double,
    val projectedCostCents: Long,
    val confidenceLevel: String, // HIGH, MEDIUM, LOW
)

data class UsageTrends(
    val trend: String, // INCREASING, DECREASING, STABLE, INSUFFICIENT_DATA
    val averageMonthlyUsageKwh: Double?,
    val highestUsageMonth: YearMonth?,
    val lowestUsageMonth: YearMonth?,
    val volatilityScore: Double?, // Percentage
)

data class UsageChartData(
    val dataPoints: List<ChartDataPoint>,
)

data class ChartDataPoint(
    val month: String,
    val usageKwh: Double,
    val costCents: Long,
)
