package com.example.usbilling.casemanagement.repository

import com.example.usbilling.casemanagement.domain.CaseNote
import com.example.usbilling.casemanagement.domain.CaseRecord
import com.example.usbilling.casemanagement.domain.CaseStatus
import com.example.usbilling.casemanagement.domain.CaseStatusHistory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class CaseRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {

    /**
     * Save a case record using bitemporal SCD2 pattern.
     * Creates a new row with system_from = now, system_to = 9999-12-31.
     * If updating, supersedes the current version by setting its system_to = now.
     */
    fun save(caseRecord: CaseRecord): CaseRecord {
        // First, supersede the current version if it exists
        val currentVersion = findCurrentVersion(caseRecord.caseId)
        if (currentVersion != null) {
            supersedeCurrent(caseRecord.caseId)
        }

        // Insert new version
        val sql = """
            INSERT INTO case_record (
                case_id, case_number, utility_id, account_id, customer_id,
                case_type, case_category, status, priority, title, description,
                opened_by, opened_at, assigned_to, assigned_team,
                resolved_at, closed_at, resolution_notes,
                system_from, system_to, modified_by
            ) VALUES (
                :caseId, :caseNumber, :utilityId, :accountId, :customerId,
                :caseType, :caseCategory, :status, :priority, :title, :description,
                :openedBy, :openedAt, :assignedTo, :assignedTeam,
                :resolvedAt, :closedAt, :resolutionNotes,
                :systemFrom, :systemTo, :modifiedBy
            )
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("caseId", caseRecord.caseId)
            .addValue("caseNumber", caseRecord.caseNumber)
            .addValue("utilityId", caseRecord.utilityId)
            .addValue("accountId", caseRecord.accountId)
            .addValue("customerId", caseRecord.customerId)
            .addValue("caseType", caseRecord.caseType.name)
            .addValue("caseCategory", caseRecord.caseCategory.name)
            .addValue("status", caseRecord.status.name)
            .addValue("priority", caseRecord.priority.name)
            .addValue("title", caseRecord.title)
            .addValue("description", caseRecord.description)
            .addValue("openedBy", caseRecord.openedBy)
            .addValue("openedAt", caseRecord.openedAt)
            .addValue("assignedTo", caseRecord.assignedTo)
            .addValue("assignedTeam", caseRecord.assignedTeam)
            .addValue("resolvedAt", caseRecord.resolvedAt)
            .addValue("closedAt", caseRecord.closedAt)
            .addValue("resolutionNotes", caseRecord.resolutionNotes)
            .addValue("systemFrom", caseRecord.systemFrom)
            .addValue("systemTo", caseRecord.systemTo)
            .addValue("modifiedBy", caseRecord.modifiedBy)

        jdbcTemplate.update(sql, params)
        return caseRecord
    }

    /**
     * Supersede the current version by setting system_to = CURRENT_TIMESTAMP.
     * This is the only UPDATE operation - it closes the temporal window.
     */
    private fun supersedeCurrent(caseId: String) {
        val sql = """
            UPDATE case_record 
            SET system_to = CURRENT_TIMESTAMP 
            WHERE case_id = :caseId 
              AND system_to = TIMESTAMP '9999-12-31 23:59:59'
        """.trimIndent()

        jdbcTemplate.update(sql, MapSqlParameterSource("caseId", caseId))
    }

    /**
     * Find the current version (system_to = 9999-12-31).
     */
    private fun findCurrentVersion(caseId: String): CaseRecord? {
        val sql = """
            SELECT * FROM case_record 
            WHERE case_id = :caseId 
              AND system_to = TIMESTAMP '9999-12-31 23:59:59'
        """.trimIndent()

        val params = MapSqlParameterSource("caseId", caseId)
        return jdbcTemplate.query(sql, params) { rs, _ -> mapCaseRecord(rs) }
            .firstOrNull()
    }

    /**
     * Find current version of a case (system_to = 9999-12-31).
     */
    fun findById(caseId: String): CaseRecord? {
        val sql = """
            SELECT * FROM case_record 
            WHERE case_id = :caseId 
              AND system_to = TIMESTAMP '9999-12-31 23:59:59'
        """.trimIndent()

        val params = MapSqlParameterSource("caseId", caseId)
        return jdbcTemplate.query(sql, params) { rs, _ -> mapCaseRecord(rs) }
            .firstOrNull()
    }

    /**
     * Find current versions of cases by utility (system_to = 9999-12-31).
     */
    fun findByUtilityId(
        utilityId: String,
        status: CaseStatus? = null,
        assignedTo: String? = null,
        limit: Int = 100,
    ): List<CaseRecord> {
        val sql = buildString {
            append("SELECT * FROM case_record WHERE utility_id = :utilityId")
            append(" AND system_to = TIMESTAMP '9999-12-31 23:59:59'")
            if (status != null) append(" AND status = :status")
            if (assignedTo != null) append(" AND assigned_to = :assignedTo")
            append(" ORDER BY opened_at DESC LIMIT :limit")
        }

        val params = MapSqlParameterSource()
            .addValue("utilityId", utilityId)
            .addValue("status", status?.name)
            .addValue("assignedTo", assignedTo)
            .addValue("limit", limit)

        return jdbcTemplate.query(sql, params) { rs, _ -> mapCaseRecord(rs) }
    }

    /**
     * Find current versions of cases by customer (system_to = 9999-12-31).
     */
    fun findByCustomerId(customerId: String, limit: Int = 50): List<CaseRecord> {
        val sql = """
            SELECT * FROM case_record 
            WHERE customer_id = :customerId 
              AND system_to = TIMESTAMP '9999-12-31 23:59:59'
            ORDER BY opened_at DESC 
            LIMIT :limit
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("customerId", customerId)
            .addValue("limit", limit)

        return jdbcTemplate.query(sql, params) { rs, _ -> mapCaseRecord(rs) }
    }

    fun saveNote(note: CaseNote): CaseNote {
        val sql = """
            INSERT INTO case_note (
                note_id, case_id, note_text, note_type, created_by, created_at, customer_visible
            ) VALUES (
                :noteId, :caseId, :noteText, :noteType, :createdBy, :createdAt, :customerVisible
            )
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("noteId", note.noteId)
            .addValue("caseId", note.caseId)
            .addValue("noteText", note.noteText)
            .addValue("noteType", note.noteType.name)
            .addValue("createdBy", note.createdBy)
            .addValue("createdAt", note.createdAt)
            .addValue("customerVisible", note.customerVisible)

        jdbcTemplate.update(sql, params)
        return note
    }

    fun findNotesByCaseId(caseId: String): List<CaseNote> {
        val sql = """
            SELECT * FROM case_note WHERE case_id = :caseId ORDER BY created_at ASC
        """.trimIndent()

        val params = MapSqlParameterSource("caseId", caseId)
        return jdbcTemplate.query(sql, params) { rs, _ -> mapCaseNote(rs) }
    }

    fun saveStatusHistory(history: CaseStatusHistory): CaseStatusHistory {
        val sql = """
            INSERT INTO case_status_history (
                history_id, case_id, from_status, to_status, changed_at, changed_by, reason
            ) VALUES (
                :historyId, :caseId, :fromStatus, :toStatus, :changedAt, :changedBy, :reason
            )
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("historyId", history.historyId)
            .addValue("caseId", history.caseId)
            .addValue("fromStatus", history.fromStatus?.name)
            .addValue("toStatus", history.toStatus.name)
            .addValue("changedAt", history.changedAt)
            .addValue("changedBy", history.changedBy)
            .addValue("reason", history.reason)

        jdbcTemplate.update(sql, params)
        return history
    }

    private fun mapCaseRecord(rs: ResultSet) = CaseRecord(
        caseId = rs.getString("case_id"),
        caseNumber = rs.getString("case_number"),
        utilityId = rs.getString("utility_id"),
        accountId = rs.getString("account_id"),
        customerId = rs.getString("customer_id"),
        caseType = com.example.usbilling.casemanagement.domain.CaseType.valueOf(rs.getString("case_type")),
        caseCategory = com.example.usbilling.casemanagement.domain.CaseCategory.valueOf(rs.getString("case_category")),
        status = com.example.usbilling.casemanagement.domain.CaseStatus.valueOf(rs.getString("status")),
        priority = com.example.usbilling.casemanagement.domain.CasePriority.valueOf(rs.getString("priority")),
        title = rs.getString("title"),
        description = rs.getString("description"),
        openedBy = rs.getString("opened_by"),
        openedAt = rs.getTimestamp("opened_at").toLocalDateTime(),
        assignedTo = rs.getString("assigned_to"),
        assignedTeam = rs.getString("assigned_team"),
        resolvedAt = rs.getTimestamp("resolved_at")?.toLocalDateTime(),
        closedAt = rs.getTimestamp("closed_at")?.toLocalDateTime(),
        resolutionNotes = rs.getString("resolution_notes"),
        systemFrom = rs.getTimestamp("system_from").toLocalDateTime(),
        systemTo = rs.getTimestamp("system_to").toLocalDateTime(),
        modifiedBy = rs.getString("modified_by"),
    )

    private fun mapCaseNote(rs: ResultSet) = CaseNote(
        noteId = rs.getString("note_id"),
        caseId = rs.getString("case_id"),
        noteText = rs.getString("note_text"),
        noteType = com.example.usbilling.casemanagement.domain.NoteType.valueOf(rs.getString("note_type")),
        createdBy = rs.getString("created_by"),
        createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
        customerVisible = rs.getBoolean("customer_visible"),
    )
}
