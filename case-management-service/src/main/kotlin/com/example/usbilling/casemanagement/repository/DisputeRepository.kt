package com.example.usbilling.casemanagement.repository

import com.example.usbilling.casemanagement.domain.*
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class DisputeRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {

    fun save(dispute: BillingDispute): BillingDispute {
        val sql = """
            INSERT INTO billing_dispute (
                dispute_id, utility_id, customer_id, account_id, bill_id,
                dispute_type, dispute_reason, disputed_amount_cents,
                status, priority, submitted_at, resolved_at, case_id, created_by
            ) VALUES (
                :disputeId, :utilityId, :customerId, :accountId, :billId,
                :disputeType, :disputeReason, :disputedAmountCents,
                :status, :priority, :submittedAt, :resolvedAt, :caseId, :createdBy
            )
            ON CONFLICT (dispute_id) DO UPDATE SET
                status = EXCLUDED.status,
                resolved_at = EXCLUDED.resolved_at,
                case_id = EXCLUDED.case_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("disputeId", dispute.disputeId)
            .addValue("utilityId", dispute.utilityId)
            .addValue("customerId", dispute.customerId)
            .addValue("accountId", dispute.accountId)
            .addValue("billId", dispute.billId)
            .addValue("disputeType", dispute.disputeType.name)
            .addValue("disputeReason", dispute.disputeReason)
            .addValue("disputedAmountCents", dispute.disputedAmountCents)
            .addValue("status", dispute.status.name)
            .addValue("priority", dispute.priority.name)
            .addValue("submittedAt", dispute.submittedAt)
            .addValue("resolvedAt", dispute.resolvedAt)
            .addValue("caseId", dispute.caseId)
            .addValue("createdBy", dispute.createdBy)

        jdbcTemplate.update(sql, params)
        return dispute
    }

    fun findById(disputeId: String): BillingDispute? {
        val sql = """
            SELECT * FROM billing_dispute WHERE dispute_id = :disputeId
        """.trimIndent()

        val params = MapSqlParameterSource("disputeId", disputeId)
        return jdbcTemplate.query(sql, params) { rs, _ -> mapDispute(rs) }
            .firstOrNull()
    }

    fun findByCustomerId(customerId: String, limit: Int = 50): List<BillingDispute> {
        val sql = """
            SELECT * FROM billing_dispute 
            WHERE customer_id = :customerId 
            ORDER BY submitted_at DESC 
            LIMIT :limit
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("customerId", customerId)
            .addValue("limit", limit)

        return jdbcTemplate.query(sql, params) { rs, _ -> mapDispute(rs) }
    }

    fun findByAccountId(accountId: String): List<BillingDispute> {
        val sql = """
            SELECT * FROM billing_dispute 
            WHERE account_id = :accountId 
            ORDER BY submitted_at DESC
        """.trimIndent()

        val params = MapSqlParameterSource("accountId", accountId)
        return jdbcTemplate.query(sql, params) { rs, _ -> mapDispute(rs) }
    }

    fun findByStatus(status: DisputeStatus, limit: Int = 100): List<BillingDispute> {
        val sql = """
            SELECT * FROM billing_dispute 
            WHERE status = :status 
            ORDER BY submitted_at ASC 
            LIMIT :limit
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("status", status.name)
            .addValue("limit", limit)

        return jdbcTemplate.query(sql, params) { rs, _ -> mapDispute(rs) }
    }

    private fun mapDispute(rs: ResultSet) = BillingDispute(
        disputeId = rs.getString("dispute_id"),
        utilityId = rs.getString("utility_id"),
        customerId = rs.getString("customer_id"),
        accountId = rs.getString("account_id"),
        billId = rs.getString("bill_id"),
        disputeType = DisputeType.valueOf(rs.getString("dispute_type")),
        disputeReason = rs.getString("dispute_reason"),
        disputedAmountCents = rs.getObject("disputed_amount_cents") as? Long,
        status = DisputeStatus.valueOf(rs.getString("status")),
        priority = DisputePriority.valueOf(rs.getString("priority")),
        submittedAt = rs.getTimestamp("submitted_at").toLocalDateTime(),
        resolvedAt = rs.getTimestamp("resolved_at")?.toLocalDateTime(),
        caseId = rs.getString("case_id"),
        createdBy = rs.getString("created_by"),
    )
}

@Repository
class DisputeInvestigationRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {

    fun save(investigation: DisputeInvestigation): DisputeInvestigation {
        val sql = """
            INSERT INTO dispute_investigation (
                investigation_id, dispute_id, assigned_to, investigation_notes,
                meter_test_requested, meter_test_result, field_visit_required,
                field_visit_completed_at, findings, recommendation, created_at, updated_at
            ) VALUES (
                :investigationId, :disputeId, :assignedTo, :investigationNotes,
                :meterTestRequested, :meterTestResult, :fieldVisitRequired,
                :fieldVisitCompletedAt, :findings, :recommendation, :createdAt, :updatedAt
            )
            ON CONFLICT (investigation_id) DO UPDATE SET
                investigation_notes = EXCLUDED.investigation_notes,
                meter_test_requested = EXCLUDED.meter_test_requested,
                meter_test_result = EXCLUDED.meter_test_result,
                field_visit_required = EXCLUDED.field_visit_required,
                field_visit_completed_at = EXCLUDED.field_visit_completed_at,
                findings = EXCLUDED.findings,
                recommendation = EXCLUDED.recommendation,
                updated_at = EXCLUDED.updated_at
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("investigationId", investigation.investigationId)
            .addValue("disputeId", investigation.disputeId)
            .addValue("assignedTo", investigation.assignedTo)
            .addValue("investigationNotes", investigation.investigationNotes)
            .addValue("meterTestRequested", investigation.meterTestRequested)
            .addValue("meterTestResult", investigation.meterTestResult)
            .addValue("fieldVisitRequired", investigation.fieldVisitRequired)
            .addValue("fieldVisitCompletedAt", investigation.fieldVisitCompletedAt)
            .addValue("findings", investigation.findings)
            .addValue("recommendation", investigation.recommendation?.name)
            .addValue("createdAt", investigation.createdAt)
            .addValue("updatedAt", investigation.updatedAt)

        jdbcTemplate.update(sql, params)
        return investigation
    }

    fun findByDisputeId(disputeId: String): DisputeInvestigation? {
        val sql = """
            SELECT * FROM dispute_investigation WHERE dispute_id = :disputeId
        """.trimIndent()

        val params = MapSqlParameterSource("disputeId", disputeId)
        return jdbcTemplate.query(sql, params) { rs, _ -> mapInvestigation(rs) }
            .firstOrNull()
    }

    fun findByAssignedTo(assignedTo: String, limit: Int = 50): List<DisputeInvestigation> {
        val sql = """
            SELECT * FROM dispute_investigation 
            WHERE assigned_to = :assignedTo 
            ORDER BY created_at DESC 
            LIMIT :limit
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("assignedTo", assignedTo)
            .addValue("limit", limit)

        return jdbcTemplate.query(sql, params) { rs, _ -> mapInvestigation(rs) }
    }

    private fun mapInvestigation(rs: ResultSet) = DisputeInvestigation(
        investigationId = rs.getString("investigation_id"),
        disputeId = rs.getString("dispute_id"),
        assignedTo = rs.getString("assigned_to"),
        investigationNotes = rs.getString("investigation_notes"),
        meterTestRequested = rs.getBoolean("meter_test_requested"),
        meterTestResult = rs.getString("meter_test_result"),
        fieldVisitRequired = rs.getBoolean("field_visit_required"),
        fieldVisitCompletedAt = rs.getTimestamp("field_visit_completed_at")?.toLocalDateTime(),
        findings = rs.getString("findings"),
        recommendation = rs.getString("recommendation")?.let { InvestigationRecommendation.valueOf(it) },
        createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
        updatedAt = rs.getTimestamp("updated_at").toLocalDateTime(),
    )
}

@Repository
class DisputeResolutionRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {

    fun save(resolution: DisputeResolution): DisputeResolution {
        val sql = """
            INSERT INTO dispute_resolution (
                resolution_id, dispute_id, resolution_type, adjustment_amount_cents,
                adjustment_applied, resolution_notes, customer_notified_at,
                resolved_by, resolved_at
            ) VALUES (
                :resolutionId, :disputeId, :resolutionType, :adjustmentAmountCents,
                :adjustmentApplied, :resolutionNotes, :customerNotifiedAt,
                :resolvedBy, :resolvedAt
            )
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("resolutionId", resolution.resolutionId)
            .addValue("disputeId", resolution.disputeId)
            .addValue("resolutionType", resolution.resolutionType.name)
            .addValue("adjustmentAmountCents", resolution.adjustmentAmountCents)
            .addValue("adjustmentApplied", resolution.adjustmentApplied)
            .addValue("resolutionNotes", resolution.resolutionNotes)
            .addValue("customerNotifiedAt", resolution.customerNotifiedAt)
            .addValue("resolvedBy", resolution.resolvedBy)
            .addValue("resolvedAt", resolution.resolvedAt)

        jdbcTemplate.update(sql, params)
        return resolution
    }

    fun findByDisputeId(disputeId: String): DisputeResolution? {
        val sql = """
            SELECT * FROM dispute_resolution WHERE dispute_id = :disputeId
        """.trimIndent()

        val params = MapSqlParameterSource("disputeId", disputeId)
        return jdbcTemplate.query(sql, params) { rs, _ -> mapResolution(rs) }
            .firstOrNull()
    }

    private fun mapResolution(rs: ResultSet) = DisputeResolution(
        resolutionId = rs.getString("resolution_id"),
        disputeId = rs.getString("dispute_id"),
        resolutionType = ResolutionType.valueOf(rs.getString("resolution_type")),
        adjustmentAmountCents = rs.getObject("adjustment_amount_cents") as? Long,
        adjustmentApplied = rs.getBoolean("adjustment_applied"),
        resolutionNotes = rs.getString("resolution_notes"),
        customerNotifiedAt = rs.getTimestamp("customer_notified_at")?.toLocalDateTime(),
        resolvedBy = rs.getString("resolved_by"),
        resolvedAt = rs.getTimestamp("resolved_at").toLocalDateTime(),
    )
}
