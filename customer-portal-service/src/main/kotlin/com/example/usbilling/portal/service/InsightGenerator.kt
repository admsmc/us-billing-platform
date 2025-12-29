package com.example.usbilling.portal.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.LocalDate

@Service
class InsightGenerator(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${customer-portal.customer-service-url}")
    private val customerServiceUrl: String,
) {

    private val customerClient: WebClient by lazy {
        webClientBuilder.baseUrl(customerServiceUrl).build()
    }

    fun generateInsights(utilityId: String, accountId: String): Map<String, Any> {
        // Fetch recent usage data
        val usageData = try {
            customerClient
                .get()
                .uri("/utilities/{utilityId}/accounts/{accountId}/usage?days=30", utilityId, accountId)
                .retrieve()
                .bodyToMono<Map<String, Any>>()
                .block()
        } catch (e: Exception) {
            emptyMap<String, Any>()
        }

        val insights = mutableListOf<Map<String, String>>()

        // Generate insights based on usage patterns
        val currentUsage = (usageData?.get("currentUsage") as? Number)?.toDouble() ?: 0.0
        val averageUsage = (usageData?.get("averageUsage") as? Number)?.toDouble() ?: 0.0
        val previousUsage = (usageData?.get("previousUsage") as? Number)?.toDouble() ?: 0.0

        if (currentUsage > averageUsage * 1.2) {
            insights.add(
                mapOf(
                    "type" to "HIGH_USAGE",
                    "title" to "Higher than usual usage",
                    "description" to "Your usage is 20% higher than your average. Consider checking for leaks or inefficient appliances.",
                    "priority" to "HIGH",
                ),
            )
        }

        if (currentUsage < previousUsage * 0.9) {
            insights.add(
                mapOf(
                    "type" to "USAGE_REDUCTION",
                    "title" to "Great job conserving!",
                    "description" to "You've reduced your usage by ${((previousUsage - currentUsage) / previousUsage * 100).toInt()}% compared to last period.",
                    "priority" to "LOW",
                ),
            )
        }

        // Seasonal insights
        val currentMonth = LocalDate.now().monthValue
        if (currentMonth in 6..8) {
            insights.add(
                mapOf(
                    "type" to "SEASONAL",
                    "title" to "Summer cooling tips",
                    "description" to "Set your thermostat to 78°F when home to save on cooling costs.",
                    "priority" to "MEDIUM",
                ),
            )
        } else if (currentMonth in 12..2) {
            insights.add(
                mapOf(
                    "type" to "SEASONAL",
                    "title" to "Winter heating tips",
                    "description" to "Lower your thermostat by 7-10°F when sleeping to reduce heating costs.",
                    "priority" to "MEDIUM",
                ),
            )
        }

        return mapOf(
            "accountId" to accountId,
            "insights" to insights,
            "generatedAt" to LocalDate.now().toString(),
        )
    }

    fun generateConservationTips(utilityId: String, accountId: String): List<Map<String, String>> = listOf(
        mapOf(
            "category" to "ENERGY",
            "title" to "LED bulbs save energy",
            "description" to "Replace incandescent bulbs with LEDs to reduce lighting costs by up to 75%.",
            "potentialSavings" to "Up to \$75/year",
        ),
        mapOf(
            "category" to "WATER",
            "title" to "Fix leaky faucets",
            "description" to "A dripping faucet can waste up to 3,000 gallons per year.",
            "potentialSavings" to "Up to \$35/year",
        ),
        mapOf(
            "category" to "ENERGY",
            "title" to "Programmable thermostat",
            "description" to "Install a programmable thermostat to automatically adjust temperature when you're away.",
            "potentialSavings" to "Up to \$180/year",
        ),
        mapOf(
            "category" to "WATER",
            "title" to "Low-flow showerheads",
            "description" to "Install low-flow showerheads to reduce water usage without sacrificing pressure.",
            "potentialSavings" to "Up to \$70/year",
        ),
        mapOf(
            "category" to "ENERGY",
            "title" to "Unplug devices",
            "description" to "Unplug devices and chargers when not in use to eliminate phantom power drain.",
            "potentialSavings" to "Up to \$100/year",
        ),
        mapOf(
            "category" to "WATER",
            "title" to "Water lawn efficiently",
            "description" to "Water your lawn early morning or evening to reduce evaporation.",
            "potentialSavings" to "Up to \$40/year",
        ),
    )

    fun generateBenchmarks(utilityId: String, accountId: String): Map<String, Any> {
        // Fetch usage data and compare with similar households
        val usageData = try {
            customerClient
                .get()
                .uri("/utilities/{utilityId}/accounts/{accountId}/usage?days=30", utilityId, accountId)
                .retrieve()
                .bodyToMono<Map<String, Any>>()
                .block()
        } catch (e: Exception) {
            emptyMap<String, Any>()
        }

        val currentUsage = (usageData?.get("currentUsage") as? Number)?.toDouble() ?: 500.0

        // Simulated benchmark data (in production, this would come from analytics)
        val similarHouseholds = mapOf(
            "average" to 450.0,
            "median" to 425.0,
            "percentile25" to 350.0,
            "percentile75" to 550.0,
        )

        val percentile = when {
            currentUsage < similarHouseholds["percentile25"]!! -> "Top 25% (most efficient)"
            currentUsage < similarHouseholds["median"]!! -> "Top 50% (above average efficiency)"
            currentUsage < similarHouseholds["percentile75"]!! -> "Average efficiency"
            else -> "Below average efficiency"
        }

        return mapOf(
            "accountId" to accountId,
            "yourUsage" to currentUsage,
            "similarHouseholds" to similarHouseholds,
            "yourPercentile" to percentile,
            "comparisonMessage" to when {
                currentUsage < similarHouseholds["average"]!! ->
                    "You use ${((similarHouseholds["average"]!! - currentUsage) / similarHouseholds["average"]!! * 100).toInt()}% less than similar households"
                else ->
                    "You use ${((currentUsage - similarHouseholds["average"]!!) / similarHouseholds["average"]!! * 100).toInt()}% more than similar households"
            },
        )
    }
}
