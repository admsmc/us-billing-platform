package com.example.usbilling.customer.repository

import com.example.usbilling.customer.model.*
import com.example.usbilling.customer.outbox.OutboxRepository
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * JDBC repository for CustomerAccount with bitemporal (SCD Type 2) support.
 * 
 * Implements:
 * - getCurrentVersion: Query current system-time version at specific valid-time
 * - getVersionAt: Query historical system-time version (for auditing)
 * - supersede: Close system-time on current version, insert new version, publish event
 * 
 * All mutations publish domain events to outbox within same transaction.
 */
@Repository
class JdbcCustomerAccountRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val outboxRepository: OutboxRepository,
    private val objectMapper: ObjectMapper,
) {
    
    /**
     * Get current system-time version of account as of a specific valid-time date.
     * 
     * This is the most common query pattern: "What does the account look like on date X,
     * based on what the system currently knows?"
     */
    fun getCurrentVersion(accountId: String, asOfDate: LocalDate): CustomerAccount? {
        val now = Instant.now()
        
        return jdbcTemplate.query(
            """
            SELECT *
            FROM customer_account_effective
            WHERE account_id = ?
              AND effective_from <= ?
              AND effective_to > ?
              AND system_from <= ?
              AND system_to > ?
            ORDER BY effective_from DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> mapToCustomerAccount(rs) },
            accountId,
            asOfDate,
            asOfDate,
            Timestamp.from(now),
            Timestamp.from(now),
        ).firstOrNull()
    }
    
    /**
     * Get version of account at a specific system-time (for audit queries).
     * 
     * This allows reconstructing what the system believed at any point in history.
     * Example: "What did we think the account status was on 2024-12-01 at 3pm?"
     */
    fun getVersionAt(accountId: String, asOfDate: LocalDate, systemAsOf: Instant): CustomerAccount? {
        return jdbcTemplate.query(
            """
            SELECT *
            FROM customer_account_effective
            WHERE account_id = ?
              AND effective_from <= ?
              AND effective_to > ?
              AND system_from <= ?
              AND system_to > ?
            ORDER BY effective_from DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> mapToCustomerAccount(rs) },
            accountId,
            asOfDate,
            asOfDate,
            Timestamp.from(systemAsOf),
            Timestamp.from(systemAsOf),
        ).firstOrNull()
    }
    
    /**
     * Get all current versions across all valid-time periods.
     * Useful for displaying the complete history timeline.
     */
    fun getAllCurrentVersions(accountId: String): List<CustomerAccount> {
        val now = Instant.now()
        
        return jdbcTemplate.query(
            """
            SELECT *
            FROM customer_account_effective
            WHERE account_id = ?
              AND system_from <= ?
              AND system_to > ?
            ORDER BY effective_from
            """.trimIndent(),
            { rs, _ -> mapToCustomerAccount(rs) },
            accountId,
            Timestamp.from(now),
            Timestamp.from(now),
        )
    }
    
    /**
     * Create a new account (inserts first version).
     * Publishes AccountCreated event to outbox.
     */
    @Transactional
    fun create(account: CustomerAccount, createdBy: String, now: Instant = Instant.now()): CustomerAccount {
        val accountToInsert = account.copy(
            systemFrom = now,
            systemTo = Instant.MAX,
            createdBy = createdBy,
            modifiedBy = createdBy,
            versionSequence = 1,
            changeReason = null,
        )
        
        insertVersion(accountToInsert)
        
        // Publish AccountCreated event
        publishAccountCreatedEvent(accountToInsert, createdBy, now)
        
        return accountToInsert
    }
    
    /**
     * Supersede: Close system-time on current version, insert new version.
     * 
     * This is the core SCD Type 2 operation:
     * 1. Find current system-time version
     * 2. Close its system_to = NOW
     * 3. Insert new version with system_from = NOW
     * 4. Publish domain event
     * 
     * This preserves complete audit history - no updates in place!
     */
    @Transactional
    fun supersede(
        accountId: String,
        newVersion: CustomerAccount,
        modifiedBy: String,
        changeReason: ChangeReason,
        now: Instant = Instant.now()
    ): CustomerAccount {
        // Find current version to close
        val currentVersion = getCurrentVersion(accountId, newVersion.effectiveFrom)
            ?: error("Cannot supersede: no current version found for account $accountId at ${newVersion.effectiveFrom}")
        
        // Close system-time on current version
        val updated = jdbcTemplate.update(
            """
            UPDATE customer_account_effective
            SET system_to = ?
            WHERE account_id = ?
              AND effective_from = ?
              AND system_from = ?
              AND system_to > ?
            """.trimIndent(),
            Timestamp.from(now),
            accountId,
            currentVersion.effectiveFrom,
            Timestamp.from(currentVersion.systemFrom),
            Timestamp.from(now),
        )
        
        if (updated != 1) {
            error("Failed to close current version for account $accountId - concurrent modification?")
        }
        
        // Insert new version
        val versionToInsert = newVersion.copy(
            accountId = accountId,
            systemFrom = now,
            systemTo = Instant.MAX,
            modifiedBy = modifiedBy,
            versionSequence = currentVersion.versionSequence + 1,
            changeReason = changeReason,
        )
        
        insertVersion(versionToInsert)
        
        // Publish event based on what changed
        publishAccountUpdateEvent(currentVersion, versionToInsert, modifiedBy, changeReason, now)
        
        return versionToInsert
    }
    
    /**
     * Search accounts by utility and criteria.
     */
    fun searchAccounts(
        utilityId: UtilityId,
        customerId: CustomerId? = null,
        accountNumber: String? = null,
        status: AccountStatus? = null,
        limit: Int = 100
    ): List<CustomerAccount> {
        val now = Instant.now()
        val nowDate = LocalDate.now()
        
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()
        
        conditions.add("utility_id = ?")
        params.add(utilityId.value)
        
        if (customerId != null) {
            conditions.add("customer_id = ?")
            params.add(customerId.value)
        }
        
        if (accountNumber != null) {
            conditions.add("account_number = ?")
            params.add(accountNumber)
        }
        
        if (status != null) {
            conditions.add("account_status = ?")
            params.add(status.name)
        }
        
        // Current system-time and valid-time
        conditions.add("effective_from <= ?")
        params.add(nowDate)
        conditions.add("effective_to > ?")
        params.add(nowDate)
        conditions.add("system_from <= ?")
        params.add(Timestamp.from(now))
        conditions.add("system_to > ?")
        params.add(Timestamp.from(now))
        
        val whereClause = conditions.joinToString(" AND ")
        
        return jdbcTemplate.query(
            """
            SELECT *
            FROM customer_account_effective
            WHERE $whereClause
            ORDER BY account_number
            LIMIT ?
            """.trimIndent(),
            { rs, _ -> mapToCustomerAccount(rs) },
            *(params + limit).toTypedArray(),
        )
    }
    
    // Private helpers
    
    private fun insertVersion(account: CustomerAccount) {
        jdbcTemplate.update(
            """
            INSERT INTO customer_account_effective (
                account_id, utility_id, customer_id, account_number, account_type, account_status,
                holder_name, holder_type, identity_verified, tax_id, business_name,
                billing_address_line1, billing_address_line2, billing_city, billing_state,
                billing_postal_code, billing_country,
                effective_from, effective_to, system_from, system_to,
                created_by, modified_by, version_sequence, change_reason
            ) VALUES (
                ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?, ?
            )
            """.trimIndent(),
            account.accountId,
            account.utilityId.value,
            account.customerId.value,
            account.accountNumber,
            account.accountType.name,
            account.accountStatus.name,
            account.holder.holderName,
            account.holder.holderType.name,
            account.holder.identityVerified,
            account.holder.taxId,
            account.holder.businessName,
            account.billingAddress.addressLine1,
            account.billingAddress.addressLine2,
            account.billingAddress.city,
            account.billingAddress.state,
            account.billingAddress.postalCode,
            account.billingAddress.country,
            account.effectiveFrom,
            account.effectiveTo,
            Timestamp.from(account.systemFrom),
            Timestamp.from(account.systemTo),
            account.createdBy,
            account.modifiedBy,
            account.versionSequence,
            account.changeReason?.name,
        )
    }
    
    private fun mapToCustomerAccount(rs: ResultSet): CustomerAccount {
        return CustomerAccount(
            accountId = rs.getString("account_id"),
            utilityId = UtilityId(rs.getString("utility_id")),
            customerId = CustomerId(rs.getString("customer_id")),
            accountNumber = rs.getString("account_number"),
            accountType = AccountType.valueOf(rs.getString("account_type")),
            accountStatus = AccountStatus.valueOf(rs.getString("account_status")),
            holder = AccountHolder(
                holderName = rs.getString("holder_name"),
                holderType = HolderType.valueOf(rs.getString("holder_type")),
                identityVerified = rs.getBoolean("identity_verified"),
                taxId = rs.getString("tax_id"),
                businessName = rs.getString("business_name"),
            ),
            serviceAddress = null, // Loaded separately if needed
            billingAddress = BillingAddress(
                addressLine1 = rs.getString("billing_address_line1"),
                addressLine2 = rs.getString("billing_address_line2"),
                city = rs.getString("billing_city"),
                state = rs.getString("billing_state"),
                postalCode = rs.getString("billing_postal_code"),
                country = rs.getString("billing_country"),
            ),
            contactMethods = emptyList(), // Loaded separately if needed
            effectiveFrom = rs.getDate("effective_from").toLocalDate(),
            effectiveTo = rs.getDate("effective_to").toLocalDate(),
            systemFrom = rs.getTimestamp("system_from").toInstant(),
            systemTo = rs.getTimestamp("system_to").toInstant(),
            createdBy = rs.getString("created_by"),
            modifiedBy = rs.getString("modified_by"),
            versionSequence = rs.getInt("version_sequence"),
            changeReason = rs.getString("change_reason")?.let { ChangeReason.valueOf(it) },
        )
    }
    
    private fun publishAccountCreatedEvent(account: CustomerAccount, createdBy: String, now: Instant) {
        val eventId = "account-created-${UUID.randomUUID()}"
        val eventJson = objectMapper.writeValueAsString(
            mapOf(
                "eventId" to eventId,
                "utilityId" to account.utilityId.value,
                "occurredAt" to now.toString(),
                "causedBy" to createdBy,
                "accountId" to account.accountId,
                "customerId" to account.customerId.value,
                "accountNumber" to account.accountNumber,
                "accountType" to account.accountType.name,
                "holderName" to account.holder.holderName,
                "effectiveFrom" to account.effectiveFrom.toString(),
            )
        )
        
        outboxRepository.enqueue(
            topic = "customer-account-events",
            eventKey = account.accountId,
            eventType = "AccountCreated",
            eventId = eventId,
            aggregateId = account.accountId,
            payloadJson = eventJson,
            now = now,
        )
    }
    
    private fun publishAccountUpdateEvent(
        oldVersion: CustomerAccount,
        newVersion: CustomerAccount,
        modifiedBy: String,
        changeReason: ChangeReason,
        now: Instant
    ) {
        // Determine event type based on what changed
        val eventType = when {
            oldVersion.accountStatus != newVersion.accountStatus -> {
                when (newVersion.accountStatus) {
                    AccountStatus.ACTIVE -> "AccountActivated"
                    AccountStatus.SUSPENDED -> "AccountSuspended"
                    AccountStatus.CLOSED -> "AccountClosed"
                    else -> "AccountDetailsUpdated"
                }
            }
            else -> "AccountDetailsUpdated"
        }
        
        val eventId = "${eventType.lowercase()}-${UUID.randomUUID()}"
        val eventJson = objectMapper.writeValueAsString(
            mapOf(
                "eventId" to eventId,
                "utilityId" to newVersion.utilityId.value,
                "occurredAt" to now.toString(),
                "causedBy" to modifiedBy,
                "accountId" to newVersion.accountId,
                "customerId" to newVersion.customerId.value,
                "changeReason" to changeReason.name,
                "oldStatus" to oldVersion.accountStatus.name,
                "newStatus" to newVersion.accountStatus.name,
            )
        )
        
        outboxRepository.enqueue(
            topic = "customer-account-events",
            eventKey = newVersion.accountId,
            eventType = eventType,
            eventId = eventId,
            aggregateId = newVersion.accountId,
            payloadJson = eventJson,
            now = now,
        )
    }
}
