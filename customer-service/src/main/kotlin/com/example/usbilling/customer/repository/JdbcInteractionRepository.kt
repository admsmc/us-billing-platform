package com.example.usbilling.customer.repository

import com.example.usbilling.customer.model.*
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Repository for customer interactions (append-only, immutable).
 */
@Repository
class JdbcInteractionRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    
    fun insert(interaction: CustomerInteraction): CustomerInteraction {
        jdbcTemplate.update(
            """
            INSERT INTO customer_interaction (
                interaction_id, utility_id, account_id, customer_id,
                interaction_type, interaction_channel, interaction_reason, direction,
                initiated_by, summary, details, outcome,
                follow_up_required, follow_up_date, duration_seconds, sentiment,
                timestamp, tags, related_case_id, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            interaction.interactionId,
            interaction.utilityId.value,
            interaction.accountId,
            interaction.customerId?.value,
            interaction.interactionType.name,
            interaction.interactionChannel.name,
            interaction.interactionReason.name,
            interaction.direction.name,
            interaction.initiatedBy,
            interaction.summary,
            interaction.details,
            interaction.outcome?.name,
            interaction.followUpRequired,
            interaction.followUpDate,
            interaction.durationSeconds,
            interaction.sentiment?.name,
            Timestamp.from(interaction.timestamp),
            jdbcTemplate.dataSource?.connection?.createArrayOf("TEXT", interaction.tags.toTypedArray()),
            interaction.relatedCaseId,
            Timestamp.from(Instant.now()),
        )
        
        return interaction
    }
    
    fun findByAccount(accountId: String, limit: Int = 100): List<CustomerInteraction> {
        return jdbcTemplate.query(
            """
            SELECT *
            FROM customer_interaction
            WHERE account_id = ?
            ORDER BY timestamp DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ -> mapToInteraction(rs) },
            accountId,
            limit,
        )
    }
    
    fun findByCustomer(customerId: CustomerId, limit: Int = 100): List<CustomerInteraction> {
        return jdbcTemplate.query(
            """
            SELECT *
            FROM customer_interaction
            WHERE customer_id = ?
            ORDER BY timestamp DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ -> mapToInteraction(rs) },
            customerId.value,
            limit,
        )
    }
    
    fun findByUtility(utilityId: UtilityId, limit: Int = 100): List<CustomerInteraction> {
        return jdbcTemplate.query(
            """
            SELECT *
            FROM customer_interaction
            WHERE utility_id = ?
            ORDER BY timestamp DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ -> mapToInteraction(rs) },
            utilityId.value,
            limit,
        )
    }
    
    fun findById(interactionId: String): CustomerInteraction? {
        return jdbcTemplate.query(
            """
            SELECT *
            FROM customer_interaction
            WHERE interaction_id = ?
            """.trimIndent(),
            { rs, _ -> mapToInteraction(rs) },
            interactionId,
        ).firstOrNull()
    }
    
    private fun mapToInteraction(rs: ResultSet): CustomerInteraction {
        val tagsArray = rs.getArray("tags")
        val tags = if (tagsArray != null) {
            (tagsArray.array as Array<*>).mapNotNull { it as? String }
        } else {
            emptyList()
        }
        
        return CustomerInteraction(
            interactionId = rs.getString("interaction_id"),
            utilityId = UtilityId(rs.getString("utility_id")),
            accountId = rs.getString("account_id"),
            customerId = rs.getString("customer_id")?.let { CustomerId(it) },
            interactionType = InteractionType.valueOf(rs.getString("interaction_type")),
            interactionChannel = InteractionChannel.valueOf(rs.getString("interaction_channel")),
            interactionReason = InteractionReason.valueOf(rs.getString("interaction_reason")),
            direction = InteractionDirection.valueOf(rs.getString("direction")),
            initiatedBy = rs.getString("initiated_by"),
            summary = rs.getString("summary"),
            details = rs.getString("details"),
            outcome = rs.getString("outcome")?.let { InteractionOutcome.valueOf(it) },
            followUpRequired = rs.getBoolean("follow_up_required"),
            followUpDate = rs.getDate("follow_up_date")?.toLocalDate(),
            durationSeconds = rs.getObject("duration_seconds") as? Int,
            timestamp = rs.getTimestamp("timestamp").toInstant(),
            sentiment = rs.getString("sentiment")?.let { InteractionSentiment.valueOf(it) },
            tags = tags,
            relatedCaseId = rs.getString("related_case_id"),
        )
    }
}
