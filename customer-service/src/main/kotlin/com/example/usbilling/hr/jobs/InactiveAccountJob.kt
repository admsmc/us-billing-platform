package com.example.usbilling.hr.jobs

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.time.LocalDate

/**
 * Proactive monitoring job that detects accounts with no usage for 60+ days.
 *
 * Detection criteria:
 * - No increase in meter readings for 60+ consecutive days
 * - Account is active (not closed)
 * - Account had previous usage history
 *
 * When detected, creates a case to follow up with customer to determine:
 * - Is the property vacant?
 * - Should the account be closed?
 * - Is the meter malfunctioning?
 * - Is there a safety concern (gas service with no usage)?
 */
@Component
class InactiveAccountJob(
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
     * Run inactive account detection weekly on Mondays at 4 AM.
     * Checks for accounts with no usage for 60+ days.
     */
    @Scheduled(cron = "0 0 4 * * MON")
    fun detectInactiveAccounts() {
        logger.info("Starting inactive account detection job")

        try {
            val inactiveAccounts = findInactiveAccounts()

            logger.info("Found ${inactiveAccounts.size} inactive accounts")

            inactiveAccounts.forEach { account ->
                handleInactiveAccount(account)
            }

            logger.info("Inactive account detection job completed. Processed ${inactiveAccounts.size} accounts")
        } catch (e: Exception) {
            logger.error("Inactive account detection job failed", e)
        }
    }

    /**
     * Find accounts with no usage for 60+ days.
     */
    private fun findInactiveAccounts(): List<InactiveAccount> {
        val sql = """
            WITH latest_reads AS (
                SELECT 
                    mr.account_id,
                    mr.meter_id,
                    mr.read_value,
                    mr.read_timestamp,
                    LAG(mr.read_value) OVER (PARTITION BY mr.account_id ORDER BY mr.read_timestamp) AS prev_value,
                    LAG(mr.read_timestamp) OVER (PARTITION BY mr.account_id ORDER BY mr.read_timestamp) AS prev_timestamp,
                    ROW_NUMBER() OVER (PARTITION BY mr.account_id ORDER BY mr.read_timestamp DESC) AS rn
                FROM meter_read mr
            ),
            no_usage_accounts AS (
                SELECT 
                    lr.account_id,
                    lr.meter_id,
                    lr.read_value,
                    lr.read_timestamp AS last_read_date,
                    lr.prev_value,
                    lr.prev_timestamp AS prev_read_date
                FROM latest_reads lr
                WHERE lr.rn = 1
                  AND lr.read_value = lr.prev_value  -- No change in meter reading
                  AND lr.read_timestamp >= NOW() - INTERVAL '90 DAYS'  -- At least one recent read
            ),
            historical_check AS (
                SELECT 
                    nua.account_id,
                    nua.meter_id,
                    nua.last_read_date,
                    nua.read_value,
                    MIN(mr.read_timestamp) AS first_no_usage_date,
                    MAX(CASE WHEN mr.read_value < nua.read_value THEN mr.read_timestamp END) AS last_usage_date
                FROM no_usage_accounts nua
                JOIN meter_read mr ON mr.account_id = nua.account_id AND mr.meter_id = nua.meter_id
                WHERE mr.read_value <= nua.read_value
                GROUP BY nua.account_id, nua.meter_id, nua.last_read_date, nua.read_value
            )
            SELECT 
                hc.account_id,
                hc.meter_id,
                a.customer_id,
                a.utility_id,
                a.account_status,
                m.service_type,
                hc.first_no_usage_date,
                hc.last_usage_date,
                hc.last_read_date,
                EXTRACT(DAY FROM (hc.last_read_date - COALESCE(hc.last_usage_date, hc.first_no_usage_date))) AS days_inactive
            FROM historical_check hc
            JOIN account a ON a.account_id = hc.account_id
            JOIN meter m ON m.meter_id = hc.meter_id
            WHERE a.account_status = 'ACTIVE'
              AND EXTRACT(DAY FROM (hc.last_read_date - COALESCE(hc.last_usage_date, hc.first_no_usage_date))) >= 60
              AND NOT EXISTS (
                  -- Exclude if we already created an inactive account case in the past 60 days
                  SELECT 1 FROM case_record cr
                  WHERE cr.account_id = hc.account_id
                    AND cr.case_type = 'INACTIVE_ACCOUNT'
                    AND cr.created_at >= NOW() - INTERVAL '60 DAYS'
              )
            ORDER BY days_inactive DESC
        """.trimIndent()

        return jdbcTemplate.query(sql) { rs, _ ->
            InactiveAccount(
                accountId = rs.getString("account_id"),
                meterId = rs.getString("meter_id"),
                customerId = rs.getString("customer_id"),
                utilityId = rs.getString("utility_id"),
                accountStatus = rs.getString("account_status"),
                serviceType = rs.getString("service_type"),
                firstNoUsageDate = rs.getDate("first_no_usage_date").toLocalDate(),
                lastUsageDate = rs.getDate("last_usage_date")?.toLocalDate(),
                lastReadDate = rs.getDate("last_read_date").toLocalDate(),
                daysInactive = rs.getInt("days_inactive"),
            )
        }
    }

    /**
     * Handle an inactive account by creating a case and notifying the customer.
     */
    private fun handleInactiveAccount(account: InactiveAccount) {
        logger.info("Handling inactive account ${account.accountId}")

        try {
            // Create case
            val caseId = createInactiveAccountCase(account)

            // Send notification
            notifyCustomer(account, caseId)

            logger.info("Created inactive account case $caseId for account ${account.accountId}")
        } catch (e: Exception) {
            logger.error("Failed to handle inactive account ${account.accountId}", e)
        }
    }

    /**
     * Create a case for the inactive account.
     */
    private fun createInactiveAccountCase(account: InactiveAccount): String {
        val safetyNote = if (account.serviceType == "GAS") {
            "\n\nIMPORTANT: Gas service with no usage may indicate a safety concern or pilot light issue."
        } else {
            ""
        }

        val description = """
            Account has been inactive with no usage for ${account.daysInactive} days.
            
            Account: ${account.accountId}
            Meter: ${account.meterId}
            Service Type: ${account.serviceType}
            Last usage date: ${account.lastUsageDate ?: "Unknown"}
            Last meter read: ${account.lastReadDate}
            Days inactive: ${account.daysInactive}
            $safetyNote
            
            Follow-up actions needed:
            1. Contact customer to verify property occupancy
            2. Determine if account should be closed
            3. Check if meter is functioning properly
            4. If property is vacant, discuss account closure or suspension options
        """.trimIndent()

        val priority = if (account.serviceType == "GAS" && account.daysInactive >= 90) "HIGH" else "MEDIUM"

        val response = caseClient
            .post()
            .uri("/utilities/{utilityId}/cases", account.utilityId)
            .bodyValue(
                mapOf(
                    "customerId" to account.customerId,
                    "accountId" to account.accountId,
                    "caseType" to "INACTIVE_ACCOUNT",
                    "category" to "ACCOUNT",
                    "priority" to priority,
                    "subject" to "Account Inactive - No Usage for ${account.daysInactive} Days",
                    "description" to description,
                    "channel" to "SYSTEM",
                    "assignedTo" to "CUSTOMER_SERVICE",
                ),
            )
            .retrieve()
            .bodyToMono(Map::class.java)
            .block()

        return response?.get("caseId") as? String ?: ""
    }

    /**
     * Send notification to customer about inactive account.
     */
    private fun notifyCustomer(account: InactiveAccount, caseId: String) {
        val message = """
            We noticed your ${account.serviceType.lowercase()} account has shown no usage for ${account.daysInactive} days.
            
            Account: ${account.accountId}
            Service: ${account.serviceType}
            Last usage: ${account.lastUsageDate ?: "More than ${account.daysInactive} days ago"}
            
            If your property is vacant or you've moved, you may want to close or suspend your account to avoid monthly service charges.
            
            If you're still occupying the property:
            • Please check if your ${account.serviceType.lowercase()} service is working properly
            ${if (account.serviceType == "GAS") "• Verify pilot lights are functioning\n• Ensure gas appliances are operational" else ""}
            
            We're here to help. Please contact us if you have questions or need assistance.
            
            Case #: $caseId
        """.trimIndent()

        notificationClient
            .post()
            .uri("/utilities/{utilityId}/notifications/send", account.utilityId)
            .bodyValue(
                mapOf(
                    "customerId" to account.customerId,
                    "channel" to "EMAIL",
                    "subject" to "Account Activity Notice - No Usage Detected",
                    "message" to message,
                    "priority" to "NORMAL",
                ),
            )
            .retrieve()
            .bodyToMono(Map::class.java)
            .block()
    }
}

data class InactiveAccount(
    val accountId: String,
    val meterId: String,
    val customerId: String,
    val utilityId: String,
    val accountStatus: String,
    val serviceType: String,
    val firstNoUsageDate: LocalDate,
    val lastUsageDate: LocalDate?,
    val lastReadDate: LocalDate,
    val daysInactive: Int,
)
