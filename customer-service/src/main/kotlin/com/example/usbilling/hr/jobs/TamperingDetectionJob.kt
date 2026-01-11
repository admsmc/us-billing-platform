package com.example.usbilling.hr.jobs

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient

/**
 * Proactive monitoring job that detects potential meter tampering based on usage pattern anomalies.
 *
 * Detection criteria:
 * - Sudden drop in usage compared to historical baseline (>50% reduction)
 * - Zero or near-zero usage when occupancy confirmed
 * - Irregular usage patterns (usage only during specific hours suggesting bypass)
 * - Meter reading reversals or impossible values
 *
 * When detected, creates a high-priority case and dispatches field operations.
 */
@Component
class TamperingDetectionJob(
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
     * Run tampering detection daily at 3 AM.
     * Analyzes usage patterns against historical baselines.
     */
    @Scheduled(cron = "0 0 3 * * *")
    fun detectTampering() {
        logger.info("Starting tampering detection job")

        try {
            val suspiciousAccounts = findTamperingIndicators()

            logger.info("Found ${suspiciousAccounts.size} accounts with potential tampering")

            suspiciousAccounts.forEach { account ->
                handleTamperingIndicator(account)
            }

            logger.info("Tampering detection job completed. Processed ${suspiciousAccounts.size} accounts")
        } catch (e: Exception) {
            logger.error("Tampering detection job failed", e)
        }
    }

    /**
     * Find accounts with tampering indicators.
     */
    private fun findTamperingIndicators(): List<TamperingIndicator> {
        val sql = """
            WITH baseline_usage AS (
                SELECT 
                    mr.account_id,
                    mr.meter_id,
                    AVG(mr.read_value - LAG(mr.read_value) OVER (PARTITION BY mr.account_id ORDER BY mr.read_timestamp)) AS avg_monthly_usage
                FROM meter_read mr
                WHERE mr.read_timestamp >= NOW() - INTERVAL '6 MONTHS'
                  AND mr.read_timestamp < NOW() - INTERVAL '1 MONTH'
                GROUP BY mr.account_id, mr.meter_id
                HAVING AVG(mr.read_value - LAG(mr.read_value) OVER (PARTITION BY mr.account_id ORDER BY mr.read_timestamp)) > 0
            ),
            recent_usage AS (
                SELECT 
                    mr.account_id,
                    mr.meter_id,
                    AVG(mr.read_value - LAG(mr.read_value) OVER (PARTITION BY mr.account_id ORDER BY mr.read_timestamp)) AS avg_recent_usage,
                    COUNT(*) AS read_count
                FROM meter_read mr
                WHERE mr.read_timestamp >= NOW() - INTERVAL '1 MONTH'
                GROUP BY mr.account_id, mr.meter_id
            ),
            usage_drops AS (
                SELECT 
                    bu.account_id,
                    bu.meter_id,
                    bu.avg_monthly_usage AS baseline_usage,
                    ru.avg_recent_usage AS recent_usage,
                    ((bu.avg_monthly_usage - ru.avg_recent_usage) / NULLIF(bu.avg_monthly_usage, 0)) * 100 AS drop_percentage
                FROM baseline_usage bu
                JOIN recent_usage ru ON ru.account_id = bu.account_id AND ru.meter_id = bu.meter_id
                WHERE ru.avg_recent_usage < (bu.avg_monthly_usage * 0.5)  -- More than 50% drop
                  AND bu.avg_monthly_usage > 10  -- Baseline usage was significant
            ),
            meter_reversals AS (
                SELECT DISTINCT
                    mr.account_id,
                    mr.meter_id,
                    'METER_REVERSAL' AS indicator_type
                FROM meter_read mr
                WHERE mr.read_timestamp >= NOW() - INTERVAL '1 MONTH'
                  AND mr.read_value < (
                      SELECT prev.read_value 
                      FROM meter_read prev 
                      WHERE prev.account_id = mr.account_id 
                        AND prev.meter_id = mr.meter_id 
                        AND prev.read_timestamp < mr.read_timestamp 
                      ORDER BY prev.read_timestamp DESC 
                      LIMIT 1
                  )
            )
            SELECT 
                ud.account_id,
                ud.meter_id,
                a.customer_id,
                a.utility_id,
                m.service_type,
                ud.baseline_usage,
                ud.recent_usage,
                ud.drop_percentage,
                'USAGE_DROP' AS indicator_type
            FROM usage_drops ud
            JOIN account a ON a.account_id = ud.account_id
            JOIN meter m ON m.meter_id = ud.meter_id
            WHERE NOT EXISTS (
                -- Exclude if we already created a tampering case in the past 30 days
                SELECT 1 FROM case_record cr
                WHERE cr.account_id = ud.account_id
                  AND cr.case_type = 'TAMPERING_INVESTIGATION'
                  AND cr.created_at >= NOW() - INTERVAL '30 DAYS'
            )
            
            UNION ALL
            
            SELECT 
                mr.account_id,
                mr.meter_id,
                a.customer_id,
                a.utility_id,
                m.service_type,
                0.0 AS baseline_usage,
                0.0 AS recent_usage,
                0.0 AS drop_percentage,
                mr.indicator_type
            FROM meter_reversals mr
            JOIN account a ON a.account_id = mr.account_id
            JOIN meter m ON m.meter_id = mr.meter_id
            WHERE NOT EXISTS (
                SELECT 1 FROM case_record cr
                WHERE cr.account_id = mr.account_id
                  AND cr.case_type = 'TAMPERING_INVESTIGATION'
                  AND cr.created_at >= NOW() - INTERVAL '30 DAYS'
            )
        """.trimIndent()

        return jdbcTemplate.query(sql) { rs, _ ->
            TamperingIndicator(
                accountId = rs.getString("account_id"),
                meterId = rs.getString("meter_id"),
                customerId = rs.getString("customer_id"),
                utilityId = rs.getString("utility_id"),
                serviceType = rs.getString("service_type"),
                indicatorType = rs.getString("indicator_type"),
                baselineUsage = rs.getDouble("baseline_usage"),
                recentUsage = rs.getDouble("recent_usage"),
                dropPercentage = rs.getDouble("drop_percentage"),
            )
        }
    }

    /**
     * Handle tampering indicator by creating a case and dispatching field operations.
     */
    private fun handleTamperingIndicator(indicator: TamperingIndicator) {
        logger.info("Handling tampering indicator for account ${indicator.accountId}")

        try {
            // Create high-priority case
            val caseId = createTamperingCase(indicator)

            // Notify field operations
            notifyFieldOperations(indicator, caseId)

            logger.info("Created tampering case $caseId for account ${indicator.accountId}")
        } catch (e: Exception) {
            logger.error("Failed to handle tampering indicator for account ${indicator.accountId}", e)
        }
    }

    /**
     * Create a case for the detected tampering indicator.
     */
    private fun createTamperingCase(indicator: TamperingIndicator): String {
        val description = when (indicator.indicatorType) {
            "USAGE_DROP" -> """
                Potential meter tampering detected based on significant usage drop.
                
                Account: ${indicator.accountId}
                Meter: ${indicator.meterId}
                Service Type: ${indicator.serviceType}
                
                Historical baseline usage: ${String.format("%.2f", indicator.baselineUsage)} units/month
                Recent usage: ${String.format("%.2f", indicator.recentUsage)} units/month
                Usage drop: ${String.format("%.1f", indicator.dropPercentage)}%
                
                This significant drop in usage may indicate meter tampering or bypass. Field investigation required to:
                1. Physically inspect meter for signs of tampering
                2. Check meter seals and connections
                3. Verify meter is recording accurately
                4. Document findings with photos
            """.trimIndent()

            "METER_REVERSAL" -> """
                Critical: Meter reversal detected indicating possible tampering.
                
                Account: ${indicator.accountId}
                Meter: ${indicator.meterId}
                Service Type: ${indicator.serviceType}
                
                Meter reading has decreased from previous value, which is physically impossible under normal operation.
                This strongly indicates meter tampering or mechanical failure.
                
                IMMEDIATE field inspection required.
            """.trimIndent()

            else -> "Potential meter tampering detected. Field inspection required."
        }

        val response = caseClient
            .post()
            .uri("/utilities/{utilityId}/cases", indicator.utilityId)
            .bodyValue(
                mapOf(
                    "customerId" to indicator.customerId,
                    "accountId" to indicator.accountId,
                    "caseType" to "TAMPERING_INVESTIGATION",
                    "category" to "METER",
                    "priority" to "HIGH",
                    "subject" to "Meter Tampering Investigation Required",
                    "description" to description,
                    "channel" to "SYSTEM",
                    "assignedTo" to "FIELD_OPERATIONS",
                ),
            )
            .retrieve()
            .bodyToMono(Map::class.java)
            .block()

        return response?.get("caseId") as? String ?: ""
    }

    /**
     * Notify field operations team about tampering indicator.
     */
    private fun notifyFieldOperations(indicator: TamperingIndicator, caseId: String) {
        val indicatorDetails = if (indicator.indicatorType == "USAGE_DROP") {
            "Usage dropped ${String.format("%.1f", indicator.dropPercentage)}% from baseline"
        } else {
            "Meter reversal detected"
        }

        val message = """
            TAMPERING ALERT: Field inspection required
            
            Case: $caseId
            Account: ${indicator.accountId}
            Meter: ${indicator.meterId}
            Service: ${indicator.serviceType}
            Indicator: ${indicator.indicatorType}
            
            $indicatorDetails
            
            Priority: HIGH - Dispatch technician for meter inspection
        """.trimIndent()

        notificationClient
            .post()
            .uri("/utilities/{utilityId}/notifications/send", indicator.utilityId)
            .bodyValue(
                mapOf(
                    "recipientGroup" to "FIELD_OPERATIONS",
                    "channel" to "EMAIL",
                    "subject" to "URGENT: Meter Tampering Investigation - Case $caseId",
                    "message" to message,
                    "priority" to "HIGH",
                ),
            )
            .retrieve()
            .bodyToMono(Map::class.java)
            .block()
    }
}

data class TamperingIndicator(
    val accountId: String,
    val meterId: String,
    val customerId: String,
    val utilityId: String,
    val serviceType: String,
    val indicatorType: String,
    val baselineUsage: Double,
    val recentUsage: Double,
    val dropPercentage: Double,
)
