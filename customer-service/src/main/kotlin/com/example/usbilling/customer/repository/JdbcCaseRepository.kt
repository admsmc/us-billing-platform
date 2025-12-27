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

@Repository
class JdbcCaseRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    
    fun insert(case: CaseRecord): CaseRecord {
        jdbcTemplate.update(
            """
            INSERT INTO case_record (
                case_id, case_number, utility_id, account_id, customer_id,
                case_type, case_category, status, priority, severity,
                title, description, resolution_notes, root_cause, preventative_action,
                assigned_to, assigned_team, opened_by, closed_by,
                estimated_resolution_date, created_at, updated_at, closed_at,
                tags, related_case_ids
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            case.caseId,
            case.caseNumber,
            case.utilityId.value,
            case.accountId,
            case.customerId?.value,
            case.caseType.name,
            case.caseCategory.name,
            case.status.name,
            case.priority.name,
            case.severity?.name,
            case.title,
            case.description,
            case.resolutionNotes,
            case.rootCause,
            case.preventativeAction,
            case.assignedTo,
            case.assignedTeam,
            case.openedBy,
            case.closedBy,
            case.estimatedResolutionDate,
            Timestamp.from(case.createdAt),
            Timestamp.from(case.updatedAt),
            case.closedAt?.let { Timestamp.from(it) },
            jdbcTemplate.dataSource?.connection?.createArrayOf("TEXT", case.tags.toTypedArray()),
            jdbcTemplate.dataSource?.connection?.createArrayOf("TEXT", case.relatedCaseIds.toTypedArray()),
        )
        
        return case
    }
    
    fun update(case: CaseRecord): Int {
        return jdbcTemplate.update(
            """
            UPDATE case_record
            SET status = ?, priority = ?, severity = ?,
                resolution_notes = ?, root_cause = ?, preventative_action = ?,
                assigned_to = ?, assigned_team = ?, closed_by = ?,
                estimated_resolution_date = ?, updated_at = ?, closed_at = ?
            WHERE case_id = ?
            """.trimIndent(),
            case.status.name,
            case.priority.name,
            case.severity?.name,
            case.resolutionNotes,
            case.rootCause,
            case.preventativeAction,
            case.assignedTo,
            case.assignedTeam,
            case.closedBy,
            case.estimatedResolutionDate,
            Timestamp.from(case.updatedAt),
            case.closedAt?.let { Timestamp.from(it) },
            case.caseId,
        )
    }
    
    fun findById(caseId: String): CaseRecord? {
        return jdbcTemplate.query(
            "SELECT * FROM case_record WHERE case_id = ?",
            { rs, _ -> mapToCase(rs) },
            caseId,
        ).firstOrNull()
    }
    
    fun searchCases(
        utilityId: UtilityId,
        accountId: String? = null,
        customerId: CustomerId? = null,
        status: CaseStatus? = null,
        priority: CasePriority? = null,
        assignedTo: String? = null,
        assignedTeam: String? = null,
        limit: Int = 100
    ): List<CaseRecord> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()
        
        conditions.add("utility_id = ?")
        params.add(utilityId.value)
        
        accountId?.let {
            conditions.add("account_id = ?")
            params.add(it)
        }
        
        customerId?.let {
            conditions.add("customer_id = ?")
            params.add(it.value)
        }
        
        status?.let {
            conditions.add("status = ?")
            params.add(it.name)
        }
        
        priority?.let {
            conditions.add("priority = ?")
            params.add(it.name)
        }
        
        assignedTo?.let {
            conditions.add("assigned_to = ?")
            params.add(it)
        }
        
        assignedTeam?.let {
            conditions.add("assigned_team = ?")
            params.add(it)
        }
        
        val whereClause = conditions.joinToString(" AND ")
        
        return jdbcTemplate.query(
            """
            SELECT * FROM case_record
            WHERE $whereClause
            ORDER BY created_at DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ -> mapToCase(rs) },
            *(params + limit).toTypedArray(),
        )
    }
    
    fun addStatusHistory(history: CaseStatusHistory) {
        jdbcTemplate.update(
            """
            INSERT INTO case_status_history (
                history_id, case_id, from_status, to_status,
                changed_by, changed_at, reason, notes
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            history.historyId,
            history.caseId,
            history.fromStatus?.name,
            history.toStatus.name,
            history.changedBy,
            Timestamp.from(history.changedAt),
            history.reason,
            history.notes,
        )
    }
    
    fun addNote(note: CaseNote) {
        jdbcTemplate.update(
            """
            INSERT INTO case_note (
                note_id, case_id, note_type, content,
                is_internal, created_by, created_at, attachment_ids
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            note.noteId,
            note.caseId,
            note.noteType.name,
            note.content,
            note.isInternal,
            note.createdBy,
            Timestamp.from(note.createdAt),
            jdbcTemplate.dataSource?.connection?.createArrayOf("TEXT", note.attachmentIds.toTypedArray()),
        )
    }
    
    fun getNotes(caseId: String, limit: Int = 100): List<CaseNote> {
        return jdbcTemplate.query(
            """
            SELECT * FROM case_note
            WHERE case_id = ?
            ORDER BY created_at DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ -> mapToNote(rs) },
            caseId,
            limit,
        )
    }
    
    private fun mapToCase(rs: ResultSet): CaseRecord {
        val tagsArray = rs.getArray("tags")
        val tags = if (tagsArray != null) {
            (tagsArray.array as Array<*>).mapNotNull { it as? String }
        } else {
            emptyList()
        }
        
        val relatedArray = rs.getArray("related_case_ids")
        val relatedIds = if (relatedArray != null) {
            (relatedArray.array as Array<*>).mapNotNull { it as? String }
        } else {
            emptyList()
        }
        
        return CaseRecord(
            caseId = rs.getString("case_id"),
            caseNumber = rs.getString("case_number"),
            utilityId = UtilityId(rs.getString("utility_id")),
            accountId = rs.getString("account_id"),
            customerId = rs.getString("customer_id")?.let { CustomerId(it) },
            caseType = CaseType.valueOf(rs.getString("case_type")),
            caseCategory = CaseCategory.valueOf(rs.getString("case_category")),
            status = CaseStatus.valueOf(rs.getString("status")),
            priority = CasePriority.valueOf(rs.getString("priority")),
            severity = rs.getString("severity")?.let { CaseSeverity.valueOf(it) },
            title = rs.getString("title"),
            description = rs.getString("description"),
            resolutionNotes = rs.getString("resolution_notes"),
            rootCause = rs.getString("root_cause"),
            preventativeAction = rs.getString("preventative_action"),
            assignedTo = rs.getString("assigned_to"),
            assignedTeam = rs.getString("assigned_team"),
            openedBy = rs.getString("opened_by"),
            closedBy = rs.getString("closed_by"),
            estimatedResolutionDate = rs.getDate("estimated_resolution_date")?.toLocalDate(),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
            closedAt = rs.getTimestamp("closed_at")?.toInstant(),
            tags = tags,
            relatedCaseIds = relatedIds,
        )
    }
    
    private fun mapToNote(rs: ResultSet): CaseNote {
        val attachArray = rs.getArray("attachment_ids")
        val attachments = if (attachArray != null) {
            (attachArray.array as Array<*>).mapNotNull { it as? String }
        } else {
            emptyList()
        }
        
        return CaseNote(
            noteId = rs.getString("note_id"),
            caseId = rs.getString("case_id"),
            noteType = CaseNoteType.valueOf(rs.getString("note_type")),
            content = rs.getString("content"),
            isInternal = rs.getBoolean("is_internal"),
            createdBy = rs.getString("created_by"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            attachmentIds = attachments,
        )
    }
}
