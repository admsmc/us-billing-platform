package com.example.usbilling.hr.jobs

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal

/**
 * Proactive monitoring job for commercial accounts approaching or exceeding credit limits.
 *
 * Detection criteria:
 * - Account has a defined credit limit
 * - Current balance is >= 80% of credit limit (warning)
 * - Current balance exceeds credit limit (critical)
 * - Account is commercial/business type
 *
 * When detected, creates a case and notifies the commercial customer to arrange payment
 * or request a credit limit increase.
 */
@Component
class CreditLimitMonitoringJob(
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
     * Run credit limit monitoring daily at 5 AM.
     * Checks commercial accounts against their credit limits.
     */
    @Scheduled(cron = "0 0 5 * * *")
    fun monitorCreditLimits() {
        logger.info("Starting credit limit monitoring job")

        try {
            val accountsAtRisk = findAccountsApproachingLimit()

            logger.info("Found ${accountsAtRisk.size} accounts approaching or exceeding credit limits")

            accountsAtRisk.forEach { account ->
                handleCreditLimitAlert(account)
            }

            logger.info("Credit limit monitoring job completed. Processed ${accountsAtRisk.size} accounts")
        } catch (e: Exception) {
            logger.error("Credit limit monitoring job failed", e)
        }
    }

    /**
     * Find commercial accounts approaching or exceeding credit limits.
     */
    private fun findAccountsApproachingLimit(): List<CreditLimitAlert> {
        val sql = """
            WITH account_balances AS (
                SELECT 
                    a.account_id,
                    a.customer_id,
                    a.utility_id,
                    a.account_type,
                    a.credit_limit,
                    COALESCE(SUM(CASE WHEN b.status NOT IN ('PAID', 'VOIDED') THEN b.total_amount ELSE 0 END), 0) AS outstanding_balance
                FROM account a
                LEFT JOIN bill b ON b.account_id = a.account_id
                WHERE a.account_type IN ('COMMERCIAL', 'INDUSTRIAL', 'BUSINESS')
                  AND a.credit_limit IS NOT NULL
                  AND a.credit_limit > 0
                  AND a.account_status = 'ACTIVE'
                GROUP BY a.account_id, a.customer_id, a.utility_id, a.account_type, a.credit_limit
            ),
            credit_utilization AS (
                SELECT 
                    ab.account_id,
                    ab.customer_id,
                    ab.utility_id,
                    ab.account_type,
                    ab.credit_limit,
                    ab.outstanding_balance,
                    (ab.outstanding_balance / ab.credit_limit) * 100 AS utilization_percentage,
                    CASE 
                        WHEN ab.outstanding_balance >= ab.credit_limit THEN 'EXCEEDED'
                        WHEN ab.outstanding_balance >= (ab.credit_limit * 0.9) THEN 'CRITICAL'
                        WHEN ab.outstanding_balance >= (ab.credit_limit * 0.8) THEN 'WARNING'
                        ELSE 'OK'
                    END AS alert_level
                FROM account_balances ab
            )
            SELECT 
                cu.account_id,
                cu.customer_id,
                cu.utility_id,
                cu.account_type,
                cu.credit_limit,
                cu.outstanding_balance,
                cu.utilization_percentage,
                cu.alert_level,
                c.customer_name,
                c.email,
                c.phone
            FROM credit_utilization cu
            JOIN customer c ON c.customer_id = cu.customer_id
            WHERE cu.alert_level IN ('WARNING', 'CRITICAL', 'EXCEEDED')
              AND NOT EXISTS (
                  -- Exclude if we already created a credit limit alert in the past 7 days at the same or higher severity
                  SELECT 1 FROM case_record cr
                  WHERE cr.account_id = cu.account_id
                    AND cr.case_type = 'CREDIT_LIMIT_ALERT'
                    AND cr.created_at >= NOW() - INTERVAL '7 DAYS'
                    AND (
                        (cu.alert_level = 'WARNING' AND cr.priority IN ('MEDIUM', 'HIGH', 'CRITICAL'))
                        OR (cu.alert_level = 'CRITICAL' AND cr.priority IN ('HIGH', 'CRITICAL'))
                        OR (cu.alert_level = 'EXCEEDED' AND cr.priority = 'CRITICAL')
                    )
              )
            ORDER BY 
                CASE cu.alert_level
                    WHEN 'EXCEEDED' THEN 1
                    WHEN 'CRITICAL' THEN 2
                    WHEN 'WARNING' THEN 3
                END,
                cu.utilization_percentage DESC
        """.trimIndent()

        return jdbcTemplate.query(sql) { rs, _ ->
            CreditLimitAlert(
                accountId = rs.getString("account_id"),
                customerId = rs.getString("customer_id"),
                utilityId = rs.getString("utility_id"),
                accountType = rs.getString("account_type"),
                customerName = rs.getString("customer_name"),
                email = rs.getString("email"),
                phone = rs.getString("phone"),
                creditLimit = rs.getBigDecimal("credit_limit"),
                outstandingBalance = rs.getBigDecimal("outstanding_balance"),
                utilizationPercentage = rs.getDouble("utilization_percentage"),
                alertLevel = rs.getString("alert_level"),
            )
        }
    }

    /**
     * Handle credit limit alert by creating a case and notifying the customer.
     */
    private fun handleCreditLimitAlert(alert: CreditLimitAlert) {
        logger.info("Handling credit limit alert for account ${alert.accountId} (${alert.alertLevel})")

        try {
            // Create case
            val caseId = createCreditLimitCase(alert)

            // Send notification
            notifyCommercialCustomer(alert, caseId)

            logger.info("Created credit limit case $caseId for account ${alert.accountId}")
        } catch (e: Exception) {
            logger.error("Failed to handle credit limit alert for account ${alert.accountId}", e)
        }
    }

    /**
     * Create a case for the credit limit alert.
     */
    private fun createCreditLimitCase(alert: CreditLimitAlert): String {
        val (priority, urgency) = when (alert.alertLevel) {
            "EXCEEDED" -> "CRITICAL" to "IMMEDIATE ACTION REQUIRED"
            "CRITICAL" -> "HIGH" to "URGENT"
            else -> "MEDIUM" to "ATTENTION NEEDED"
        }

        val description = """
            Commercial account credit limit ${if (alert.alertLevel == "EXCEEDED") "exceeded" else "approaching"}.
            
            Account: ${alert.accountId}
            Customer: ${alert.customerName}
            Account Type: ${alert.accountType}
            
            Credit Limit: ${'$'}${String.format("%,.2f", alert.creditLimit)}
            Outstanding Balance: ${'$'}${String.format("%,.2f", alert.outstandingBalance)}
            Utilization: ${String.format("%.1f", alert.utilizationPercentage)}%
            Alert Level: ${alert.alertLevel}
            
            ${
            when (alert.alertLevel) {
                "EXCEEDED" -> """
                    CRITICAL: Account has exceeded credit limit. Service may be suspended if payment is not received.
                    
                    Recommended actions:
                    1. Contact customer immediately to arrange payment
                    2. Review account for credit limit increase if appropriate
                    3. Consider payment plan options
                    4. Evaluate service suspension if necessary
                """.trimIndent()

                "CRITICAL" -> """
                    Account is at 90%+ of credit limit. Immediate attention required.
                    
                    Recommended actions:
                    1. Contact customer to arrange payment
                    2. Review recent usage patterns
                    3. Discuss credit limit increase if warranted
                    4. Set up payment plan if needed
                """.trimIndent()

                else -> """
                    Account has reached 80% of credit limit. Proactive outreach recommended.
                    
                    Recommended actions:
                    1. Notify customer of approaching limit
                    2. Offer payment options
                    3. Review credit limit adequacy
                    4. Monitor for further increases
                """.trimIndent()
            }
        }
        """.trimIndent()

        val response = caseClient
            .post()
            .uri("/utilities/{utilityId}/cases", alert.utilityId)
            .bodyValue(
                mapOf(
                    "customerId" to alert.customerId,
                    "accountId" to alert.accountId,
                    "caseType" to "CREDIT_LIMIT_ALERT",
                    "category" to "ACCOUNT",
                    "priority" to priority,
                    "subject" to "$urgency: Credit Limit ${if (alert.alertLevel == "EXCEEDED") "Exceeded" else "Alert"} - ${String.format("%.0f", alert.utilizationPercentage)}% Utilized",
                    "description" to description,
                    "channel" to "SYSTEM",
                    "assignedTo" to "COMMERCIAL_ACCOUNTS",
                ),
            )
            .retrieve()
            .bodyToMono(Map::class.java)
            .block()

        return response?.get("caseId") as? String ?: ""
    }

    /**
     * Send notification to commercial customer about credit limit.
     */
    private fun notifyCommercialCustomer(alert: CreditLimitAlert, caseId: String) {
        val subject = when (alert.alertLevel) {
            "EXCEEDED" -> "URGENT: Account Credit Limit Exceeded"
            "CRITICAL" -> "Important: Account Approaching Credit Limit"
            else -> "Notice: Account Credit Limit Alert"
        }

        val message = """
            Dear ${alert.customerName},
            
            This is an important notice regarding your commercial account credit limit.
            
            Account: ${alert.accountId}
            Credit Limit: ${'$'}${String.format("%,.2f", alert.creditLimit)}
            Current Balance: ${'$'}${String.format("%,.2f", alert.outstandingBalance)}
            Credit Utilization: ${String.format("%.1f", alert.utilizationPercentage)}%
            
            ${
            when (alert.alertLevel) {
                "EXCEEDED" -> """
                    Your account has EXCEEDED its credit limit. To avoid service interruption, please take immediate action:
                    
                    • Make a payment to reduce your balance below the credit limit
                    • Contact us to arrange a payment plan
                    • Request a credit limit review if your business needs have grown
                    
                    Service may be suspended if the outstanding balance is not addressed promptly.
                """.trimIndent()

                "CRITICAL" -> """
                    Your account balance is approaching your credit limit (90%+ utilized). We recommend:
                    
                    • Make a payment to reduce your balance
                    • Review your payment schedule
                    • Contact us about a credit limit increase if needed
                    • Consider setting up automatic payments
                """.trimIndent()

                else -> """
                    Your account balance is at 80% of your credit limit. To maintain uninterrupted service:
                    
                    • Review your current balance and payment schedule
                    • Make a payment to reduce your balance
                    • Contact us if you'd like to discuss a credit limit increase
                    • Consider enrolling in automatic payments for convenience
                """.trimIndent()
            }
        }
            
            If you have any questions or need to discuss payment options, please contact our Commercial Accounts team.
            
            Case Reference: $caseId
        """.trimIndent()

        val priority = if (alert.alertLevel == "EXCEEDED") "HIGH" else "NORMAL"

        notificationClient
            .post()
            .uri("/utilities/{utilityId}/notifications/send", alert.utilityId)
            .bodyValue(
                mapOf(
                    "customerId" to alert.customerId,
                    "channel" to "EMAIL",
                    "subject" to subject,
                    "message" to message,
                    "priority" to priority,
                ),
            )
            .retrieve()
            .bodyToMono(Map::class.java)
            .block()
    }
}

data class CreditLimitAlert(
    val accountId: String,
    val customerId: String,
    val utilityId: String,
    val accountType: String,
    val customerName: String,
    val email: String?,
    val phone: String?,
    val creditLimit: BigDecimal,
    val outstandingBalance: BigDecimal,
    val utilizationPercentage: Double,
    val alertLevel: String,
)
