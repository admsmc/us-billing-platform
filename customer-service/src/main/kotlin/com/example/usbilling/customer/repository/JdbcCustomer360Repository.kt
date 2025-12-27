package com.example.usbilling.customer.repository

import com.example.usbilling.customer.model.*
import com.example.usbilling.shared.CustomerId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

/**
 * Repository for assembling Customer 360 views for CSR workbench.
 */
@Repository
class JdbcCustomer360Repository(
    private val jdbcTemplate: JdbcTemplate,
    private val caseRepository: JdbcCaseRepository,
    private val interactionRepository: JdbcInteractionRepository,
) {
    
    /**
     * Assemble complete 360-degree view of a customer.
     */
    fun getCustomer360View(customerId: CustomerId): Customer360View? {
        val accountSummaryWithUtility = getAccountSummary(customerId) ?: return null
        
        return Customer360View(
            customerId = customerId,
            accountSummary = AccountSummary(
                accountId = accountSummaryWithUtility.accountId,
                accountNumber = accountSummaryWithUtility.accountNumber,
                accountStatus = accountSummaryWithUtility.accountStatus,
                accountType = accountSummaryWithUtility.accountType,
                currentBalance = accountSummaryWithUtility.currentBalance,
                daysDelinquent = accountSummaryWithUtility.daysDelinquent,
                serviceAddress = accountSummaryWithUtility.serviceAddress,
                billingAddress = accountSummaryWithUtility.billingAddress,
                primaryContactName = accountSummaryWithUtility.primaryContactName,
                primaryContactPhone = accountSummaryWithUtility.primaryContactPhone,
                primaryContactEmail = accountSummaryWithUtility.primaryContactEmail,
                accountOpenDate = accountSummaryWithUtility.accountOpenDate
            ),
            recentInteractions = interactionRepository.queryInteractions(
                customerId = customerId,
                limit = 10
            ),
            openCases = caseRepository.searchCases(
                utilityId = accountSummaryWithUtility.utilityId,
                customerId = customerId,
                status = null, // Get all statuses
                limit = 20
            ).filter { it.status in listOf(CaseStatus.OPEN, CaseStatus.IN_PROGRESS, CaseStatus.ESCALATED) },
            recentBills = getRecentBills(accountSummaryWithUtility.accountId),
            servicePoints = getServicePoints(accountSummaryWithUtility.accountId),
            paymentHistory = getPaymentHistory(accountSummaryWithUtility.accountId),
            alerts = getCustomerAlerts(customerId, accountSummaryWithUtility.accountId),
            lifetimeValue = getLifetimeValue(customerId, accountSummaryWithUtility.accountId),
        )
    }
    
    private fun getAccountSummary(customerId: CustomerId): AccountSummaryWithUtility? {
        return jdbcTemplate.query(
            """
            SELECT 
                ca.account_id,
                ca.account_number,
                ca.account_status,
                ca.account_type,
                ca.utility_id,
                ca.effective_from as account_open_date,
                COALESCE(sa.address_line1 || ', ' || sa.city || ', ' || sa.state_province, 'N/A') as service_address,
                COALESCE(ba.address_line1 || ', ' || ba.city || ', ' || ba.state_province, 'N/A') as billing_address,
                c.profile_first_name || ' ' || c.profile_last_name as primary_contact_name,
                cm_phone.contact_value as primary_phone,
                cm_email.contact_value as primary_email
            FROM customer_account_effective ca
            INNER JOIN account_customer_role_effective acr 
                ON ca.account_id = acr.account_id 
                AND acr.customer_id = ?
                AND acr.is_primary = true
                AND acr.system_to > NOW()
            LEFT JOIN customer_effective c 
                ON acr.customer_id = c.customer_id 
                AND c.system_to > NOW()
            LEFT JOIN service_address_effective sa 
                ON ca.service_address_id = sa.address_id 
                AND sa.system_to > NOW()
            LEFT JOIN billing_address_effective ba 
                ON ca.billing_address_id = ba.address_id 
                AND ba.system_to > NOW()
            LEFT JOIN contact_method_effective cm_phone 
                ON ca.account_id = cm_phone.account_id 
                AND cm_phone.contact_type = 'PHONE' 
                AND cm_phone.is_primary = true 
                AND cm_phone.system_to > NOW()
            LEFT JOIN contact_method_effective cm_email 
                ON ca.account_id = cm_email.account_id 
                AND cm_email.contact_type = 'EMAIL' 
                AND cm_email.is_primary = true 
                AND cm_email.system_to > NOW()
            WHERE ca.system_to > NOW()
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> mapToAccountSummaryWithUtility(rs) },
            customerId.value
        ).firstOrNull()
    }
    
    private fun getRecentBills(accountId: String): List<BillSummary> {
        // Placeholder - would query from billing-service tables
        return emptyList()
    }
    
    private fun getServicePoints(accountId: String): List<ServicePointSummary> {
        return jdbcTemplate.query(
            """
            SELECT 
                sp.service_point_id,
                sp.service_type,
                sa.address_line1 || ', ' || sa.city as address,
                m.meter_serial,
                m.last_reading_date,
                m.last_reading_value
            FROM service_connection_effective sc
            INNER JOIN service_point_effective sp 
                ON sc.service_point_id = sp.service_point_id 
                AND sp.system_to > NOW()
            LEFT JOIN service_address_effective sa 
                ON sp.address_id = sa.address_id 
                AND sa.system_to > NOW()
            LEFT JOIN meter_effective m 
                ON sc.meter_id = m.meter_id 
                AND m.system_to > NOW()
            WHERE sc.account_id = ?
              AND sc.connection_status = 'CONNECTED'
              AND sc.system_to > NOW()
            """.trimIndent(),
            { rs, _ ->
                ServicePointSummary(
                    servicePointId = rs.getString("service_point_id"),
                    serviceType = rs.getString("service_type"),
                    address = rs.getString("address"),
                    meterSerial = rs.getString("meter_serial"),
                    lastReadingDate = rs.getDate("last_reading_date")?.toLocalDate(),
                    lastReadingValue = rs.getString("last_reading_value")
                )
            },
            accountId
        )
    }
    
    private fun getPaymentHistory(accountId: String): List<PaymentSummary> {
        // Placeholder - would query from payments-service tables
        return emptyList()
    }
    
    private fun getCustomerAlerts(customerId: CustomerId, accountId: String): List<CustomerAlert> {
        val alerts = mutableListOf<CustomerAlert>()
        
        // Check for past due balance
        // Placeholder query - would integrate with billing service
        
        // Check for pending disconnection
        // Placeholder query - would integrate with service connection
        
        // Check for SLA breach cases
        val breachingCases = caseRepository.searchCases(
            utilityId = com.example.usbilling.shared.UtilityId("util-001"), // Would get from context
            customerId = customerId,
            status = CaseStatus.OPEN,
            limit = 10
        ).filter { case ->
            val ageMinutes = java.time.Duration.between(case.createdAt, java.time.Instant.now()).toMinutes()
            ageMinutes > 120 // 2 hours
        }
        
        breachingCases.forEach { case ->
            alerts.add(
                CustomerAlert(
                    alertType = AlertType.CASE_SLA_BREACH,
                    severity = AlertSeverity.WARNING,
                    message = "Case ${case.caseNumber} breaching SLA threshold",
                    actionRequired = true,
                    createdAt = case.createdAt
                )
            )
        }
        
        return alerts
    }
    
    private fun getLifetimeValue(customerId: CustomerId, accountId: String): CustomerLifetimeValue {
        // Placeholder calculations - would integrate with billing/payment history
        return CustomerLifetimeValue(
            totalRevenue = "$0.00",
            averageMonthlyRevenue = "$0.00",
            customerTenureMonths = 0,
            paymentReliabilityScore = 1.0,
            interactionCount = interactionRepository.queryInteractions(customerId = customerId, limit = 1000).size,
            caseCount = caseRepository.searchCases(
                utilityId = com.example.usbilling.shared.UtilityId("util-001"),
                customerId = customerId,
                limit = 1000
            ).size,
            escalationRate = 0.0
        )
    }
    
    private fun mapToAccountSummaryWithUtility(rs: ResultSet): AccountSummaryWithUtility {
        return AccountSummaryWithUtility(
            accountId = rs.getString("account_id"),
            accountNumber = rs.getString("account_number"),
            accountStatus = rs.getString("account_status"),
            accountType = rs.getString("account_type"),
            utilityId = com.example.usbilling.shared.UtilityId(rs.getString("utility_id")),
            currentBalance = "$0.00", // Placeholder
            daysDelinquent = null,
            serviceAddress = rs.getString("service_address"),
            billingAddress = rs.getString("billing_address"),
            primaryContactName = rs.getString("primary_contact_name") ?: "Unknown",
            primaryContactPhone = rs.getString("primary_phone"),
            primaryContactEmail = rs.getString("primary_email"),
            accountOpenDate = rs.getDate("account_open_date").toLocalDate()
        )
    }
}

/**
 * Internal type with utilityId needed for further queries.
 */
private data class AccountSummaryWithUtility(
    val accountId: String,
    val accountNumber: String,
    val accountStatus: String,
    val accountType: String,
    val utilityId: com.example.usbilling.shared.UtilityId,
    val currentBalance: String,
    val daysDelinquent: Int?,
    val serviceAddress: String,
    val billingAddress: String,
    val primaryContactName: String,
    val primaryContactPhone: String?,
    val primaryContactEmail: String?,
    val accountOpenDate: java.time.LocalDate,
)
