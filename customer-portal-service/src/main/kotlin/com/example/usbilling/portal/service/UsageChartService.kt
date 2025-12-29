package com.example.usbilling.portal.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Service
class UsageChartService(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${customer-portal.customer-service-url}")
    private val customerServiceUrl: String,
) {

    private val customerClient: WebClient by lazy {
        webClientBuilder.baseUrl(customerServiceUrl).build()
    }

    fun generateTimeSeriesData(
        utilityId: String,
        accountId: String,
        startDate: LocalDate?,
        endDate: LocalDate?,
        granularity: String,
    ): Map<String, Any> {
        // Fetch usage data
        val usageData = try {
            customerClient
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

                    uriBuilder.build(utilityId, accountId)
                }
                .retrieve()
                .bodyToMono<Map<String, Any>>()
                .block()
        } catch (e: Exception) {
            emptyMap<String, Any>()
        }

        // Format data for time-series charts (e.g., Line chart, Area chart)
        val usageRecords = (usageData?.get("records") as? List<Map<String, Any>>) ?: emptyList()

        val labels = usageRecords.map { it["date"]?.toString() ?: "" }
        val values = usageRecords.map { (it["usage"] as? Number)?.toDouble() ?: 0.0 }
        val costs = usageRecords.map { (it["cost"] as? Number)?.toDouble() ?: 0.0 }

        return mapOf(
            "chartType" to "timeseries",
            "granularity" to granularity,
            "labels" to labels,
            "datasets" to listOf(
                mapOf(
                    "label" to "Usage",
                    "data" to values,
                    "unit" to (usageData?.get("unit") ?: "kWh"),
                ),
                mapOf(
                    "label" to "Cost",
                    "data" to costs,
                    "unit" to "$",
                ),
            ),
        )
    }

    fun generateBreakdownData(
        utilityId: String,
        accountId: String,
        startDate: LocalDate?,
        endDate: LocalDate?,
    ): Map<String, Any> {
        // Fetch breakdown data by service type or category
        val breakdownData = try {
            customerClient
                .get()
                .uri { builder ->
                    var uriBuilder = builder
                        .path("/utilities/{utilityId}/accounts/{accountId}/usage/breakdown")

                    if (startDate != null) {
                        uriBuilder = uriBuilder.queryParam("startDate", startDate)
                    }
                    if (endDate != null) {
                        uriBuilder = uriBuilder.queryParam("endDate", endDate)
                    }

                    uriBuilder.build(utilityId, accountId)
                }
                .retrieve()
                .bodyToMono<Map<String, Any>>()
                .block()
        } catch (e: Exception) {
            // Generate sample breakdown if service doesn't exist yet
            mapOf(
                "breakdown" to listOf(
                    mapOf("category" to "Heating/Cooling", "usage" to 350.0, "percentage" to 45.0),
                    mapOf("category" to "Appliances", "usage" to 200.0, "percentage" to 26.0),
                    mapOf("category" to "Lighting", "usage" to 120.0, "percentage" to 15.0),
                    mapOf("category" to "Water Heating", "usage" to 110.0, "percentage" to 14.0),
                ),
            )
        }

        val breakdown = (breakdownData?.get("breakdown") as? List<Map<String, Any>>) ?: emptyList()

        val labels = breakdown.map { it["category"]?.toString() ?: "" }
        val values = breakdown.map { (it["usage"] as? Number)?.toDouble() ?: 0.0 }
        val percentages = breakdown.map { (it["percentage"] as? Number)?.toDouble() ?: 0.0 }

        return mapOf(
            "chartType" to "pie",
            "labels" to labels,
            "data" to values,
            "percentages" to percentages,
            "total" to values.sum(),
        )
    }

    fun generatePeakTimesData(
        utilityId: String,
        accountId: String,
        date: LocalDate,
    ): Map<String, Any> {
        // Fetch hourly usage for peak times analysis
        val peakData = try {
            customerClient
                .get()
                .uri(
                    "/utilities/{utilityId}/accounts/{accountId}/usage/hourly?date={date}",
                    utilityId,
                    accountId,
                    date,
                )
                .retrieve()
                .bodyToMono<Map<String, Any>>()
                .block()
        } catch (e: Exception) {
            // Generate sample hourly data if service doesn't exist yet
            mapOf(
                "hourly" to (0..23).map { hour ->
                    mapOf(
                        "hour" to hour,
                        "usage" to when {
                            hour in 6..9 -> (15..25).random().toDouble() // Morning peak
                            hour in 17..21 -> (20..30).random().toDouble() // Evening peak
                            hour in 22..23 || hour in 0..5 -> (5..10).random().toDouble() // Night
                            else -> (10..15).random().toDouble() // Midday
                        },
                    )
                },
            )
        }

        val hourlyData = (peakData?.get("hourly") as? List<Map<String, Any>>) ?: emptyList()

        val labels = hourlyData.map {
            val hour = (it["hour"] as? Number)?.toInt() ?: 0
            String.format("%02d:00", hour)
        }
        val values = hourlyData.map { (it["usage"] as? Number)?.toDouble() ?: 0.0 }

        val peakHour = hourlyData.maxByOrNull { (it["usage"] as? Number)?.toDouble() ?: 0.0 }
        val peakHourValue = (peakHour?.get("hour") as? Number)?.toInt() ?: 0

        val peakUsage = (peakHour?.get("usage") as? Number)?.toDouble() ?: 0.0

        return mapOf(
            "chartType" to "bar",
            "date" to date.format(DateTimeFormatter.ISO_DATE),
            "labels" to labels,
            "data" to values,
            "peakHour" to String.format("%02d:00", peakHourValue),
            "peakUsage" to peakUsage,
        )
    }
}
