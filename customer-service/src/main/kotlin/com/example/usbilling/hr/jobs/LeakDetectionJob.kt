package com.example.usbilling.hr.jobs

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.time.LocalDateTime

/**
 * Proactive monitoring job that detects potential water leaks based on continuous usage patterns.
 *
 * Detection criteria:
 * - Continuous water usage for 24+ consecutive hours
 * - Usage rate above minimum threshold (e.g., 0.1 gallons/minute)
 * - No significant gaps in consumption
 *
 * When detected, automatically creates a case and notifies the customer.
 */
@Component
class LeakDetectionJob(
    private val jdbcTemplate: JdbcTemplate,
    private val webClientBuilder: WebClient.Builder,
    @Value("\${customer-service.case-management-service-url:http://localhost:8091}")
    private val caseManagementServiceUrl: String,
    @Value("\${customer-service.notification-service-url:http://localhost:8092}")
    private val notificationServiceUrl: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val caseClient: WebClient by lazy {
        webClientBuilder.baseUrl(caseManagementServiceUrl).build()
    }

    private val notificationClient: WebClient by lazy {
        webClientBuilder.baseUrl(notificationServiceUrl).build()
    }

    /**
     * Run leak detection daily at 2 AM.
     * Analyzes the previous 24 hours of meter read data for water accounts.
     */
    @Scheduled(cron = "0 0 2 * * *")
    fun detectLeaks() {
        logger.info("Starting leak detection job")

        try {
            val suspiciousAccounts = findSuspiciousWaterUsage()

            logger.info("Found ${suspiciousAccounts.size} accounts with potential leaks")

            suspiciousAccounts.forEach { account ->
                handleSuspiciousAccount(account)
            }

            logger.info("Leak detection job completed. Processed ${suspiciousAccounts.size} accounts")
        } catch (e: Exception) {
            logger.error("Leak detection job failed", e)
        }
    }

    /**
     * Find accounts with continuous water usage over 24 hours.
     */
    private fun findSuspiciousWaterUsage(): List<SuspiciousAccount> {
        val sql = """
            WITH recent_reads AS (
                SELECT 
                    mr.account_id,
                    mr.meter_id,
                    mr.read_value,
                    mr.read_timestamp,
                    LAG(mr.read_value) OVER (PARTITION BY mr.account_id ORDER BY mr.read_timestamp) AS prev_value,
                    LAG(mr.read_timestamp) OVER (PARTITION BY mr.account_id ORDER BY mr.read_timestamp) AS prev_timestamp
                FROM meter_read mr
                JOIN meter m ON m.meter_id = mr.meter_id
                WHERE m.service_type = 'WATER'
                  AND mr.read_timestamp >= NOW() - INTERVAL '48 HOURS'
            ),
            usage_rates AS (
                SELECT 
                    account_id,
                    meter_id,
                    read_timestamp,
                    (read_value - prev_value) AS usage,
                    EXTRACT(EPOCH FROM (read_timestamp - prev_timestamp)) / 3600.0 AS hours_elapsed,
                    (read_value - prev_value) / NULLIF(EXTRACT(EPOCH FROM (read_timestamp - prev_timestamp)) / 3600.0, 0) AS usage_per_hour
                FROM recent_reads
                WHERE prev_value IS NOT NULL
                  AND read_value > prev_value
            ),
            continuous_usage AS (
                SELECT 
                    account_id,
                    meter_id,
                    COUNT(*) AS read_count,
                    AVG(usage_per_hour) AS avg_hourly_usage,
                    MIN(read_timestamp) AS first_detected,
                    MAX(read_timestamp) AS last_detected
                FROM usage_rates
                WHERE usage_per_hour >= 6.0  -- Minimum 6 gallons/hour (0.1 gal/min) continuous usage
                GROUP BY account_id, meter_id
                HAVING COUNT(*) >= 4  -- At least 4 continuous readings (covering 24+ hours with typical read intervals)
            )
            SELECT 
                cu.account_id,
                cu.meter_id,
                cu.avg_hourly_usage,
                cu.first_detected,
                cu.last_detected,
                a.customer_id,
                a.utility_id
            FROM continuous_usage cu
            JOIN account a ON a.account_id = cu.account_id
            WHERE NOT EXISTS (
                -- Exclude if we already created a leak case in the past 7 days
                SELECT 1 FROM case_record cr
                WHERE cr.account_id = cu.account_id
                  AND cr.case_type = 'LEAK_DETECTION'
                  AND cr.created_at >= NOW() - INTERVAL '7 DAYS'
            )
        """.trimIndent()

        return jdbcTemplate.query(sql) { rs, _ ->
            SuspiciousAccount(
                accountId = rs.getString("account_id"),
                meterId = rs.getString("meter_id"),
                customerId = rs.getString("customer_id"),
                utilityId = rs.getString("utility_id"),
                avgHourlyUsage = rs.getDouble("avg_hourly_usage"),
                firstDetected = rs.getTimestamp("first_detected").toLocalDateTime(),
                lastDetected = rs.getTimestamp("last_detected").toLocalDateTime(),
            )
        }
    }

    /**
     * Handle a suspicious account by creating a case and notifying the customer.
     */
    private fun handleSuspiciousAccount(account: SuspiciousAccount) {
        logger.info("Handling potential leak for account ${account.accountId}")

        try {
            // Create case
            val caseId = createLeakCase(account)

            // Send notification
            notifyCustomer(account, caseId)

            logger.info("Created leak case $caseId for account ${account.accountId}")
        } catch (e: Exception) {
            logger.error("Failed to handle suspicious account ${account.accountId}", e)
        }
    }

    /**
     * Create a case for the detected leak.
     */
    private fun createLeakCase(account: SuspiciousAccount): String {
        val description = """
            Potential water leak detected based on continuous usage patterns.
            
            Account: ${account.accountId}
            Meter: ${account.meterId}
            Average hourly usage: ${String.format("%.2f", account.avgHourlyUsage)} gallons/hour
            Continuous usage detected from ${account.firstDetected} to ${account.lastDetected}
            
            This may indicate a leak in the customer's plumbing system. Customer should be advised to:
            1. Check all faucets, toilets, and appliances for leaks
            2. Check for water pooling around the property
            3. Consider hiring a plumber if the leak cannot be located
        """.trimIndent()

        val response = caseClient
            .post()
            .uri("/utilities/{utilityId}/cases", account.utilityId)
            .bodyValue(
                mapOf(
                    "customerId" to account.customerId,
                    "accountId" to account.accountId,
                    "caseType" to "LEAK_DETECTION",
                    "category" to "METER",
                    "priority" to "MEDIUM",
                    "subject" to "Potential Water Leak Detected",
                    "description" to description,
                    "channel" to "SYSTEM",
                    "assignedTo" to "SYSTEM",
                ),
            )
            .retrieve()
            .bodyToMono(Map::class.java)
            .block()

        return response?.get("caseId") as? String ?: ""
    }

    /**
     * Send notification to customer about potential leak.
     */
    private fun notifyCustomer(account: SuspiciousAccount, caseId: String) {
        val message = """
            ALERT: We've detected continuous water usage on your account that may indicate a leak.
            
            Your water usage has been continuous for over 24 hours, averaging ${String.format("%.1f", account.avgHourlyUsage)} gallons per hour.
            
            Please check:
            • All faucets and toilets
            • Water heater connections
            • Outdoor hoses and sprinklers
            • Areas around your property for pooling water
            
            A leak can significantly increase your bill. If you need assistance, please contact us.
            
            Case #: $caseId
        """.trimIndent()

        notificationClient
            .post()
            .uri("/utilities/{utilityId}/notifications/send", account.utilityId)
            .bodyValue(
                mapOf(
                    "customerId" to account.customerId,
                    "channel" to "EMAIL",
                    "subject" to "Water Leak Alert - Action Required",
                    "message" to message,
                    "priority" to "HIGH",
                ),
            )
            .retrieve()
            .bodyToMono(Map::class.java)
            .block()
    }
}

data class SuspiciousAccount(
    val accountId: String,
    val meterId: String,
    val customerId: String,
    val utilityId: String,
    val avgHourlyUsage: Double,
    val firstDetected: LocalDateTime,
    val lastDetected: LocalDateTime,
)
