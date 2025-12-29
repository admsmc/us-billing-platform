package com.example.usbilling.casemanagement.service

import com.example.usbilling.casemanagement.domain.CaseCategory
import com.example.usbilling.casemanagement.domain.CaseNote
import com.example.usbilling.casemanagement.domain.CasePriority
import com.example.usbilling.casemanagement.domain.CaseRecord
import com.example.usbilling.casemanagement.domain.CaseStatus
import com.example.usbilling.casemanagement.domain.CaseStatusHistory
import com.example.usbilling.casemanagement.domain.CaseType
import com.example.usbilling.casemanagement.domain.NoteType
import com.example.usbilling.casemanagement.repository.CaseRepository
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

@Service
class CaseService(
    private val caseRepository: CaseRepository,
    private val caseRoutingService: CaseRoutingService,
) {

    fun createCase(
        utilityId: String,
        accountId: String?,
        customerId: String?,
        caseType: CaseType,
        caseCategory: CaseCategory,
        title: String,
        description: String?,
        priority: CasePriority,
        openedBy: String,
    ): CaseRecord {
        val caseId = UUID.randomUUID().toString()
        val caseNumber = generateCaseNumber(utilityId)

        val caseRecord = CaseRecord(
            caseId = caseId,
            caseNumber = caseNumber,
            utilityId = utilityId,
            accountId = accountId,
            customerId = customerId,
            caseType = caseType,
            caseCategory = caseCategory,
            status = CaseStatus.OPEN,
            priority = priority,
            title = title,
            description = description,
            openedBy = openedBy,
            openedAt = LocalDateTime.now(),
            assignedTo = null,
            assignedTeam = null,
            resolvedAt = null,
            closedAt = null,
            resolutionNotes = null,
        )

        val saved = caseRepository.save(caseRecord)

        // Auto-assign case
        caseRoutingService.autoAssignCase(saved)

        // Record status history
        recordStatusChange(caseId, null, CaseStatus.OPEN, openedBy, "Case created")

        return caseRepository.findById(caseId)!!
    }

    fun updateCaseStatus(
        caseId: String,
        newStatus: CaseStatus,
        changedBy: String,
        reason: String?,
        resolutionNotes: String? = null,
    ): CaseRecord {
        val existing = caseRepository.findById(caseId)
            ?: throw IllegalArgumentException("Case not found: $caseId")

        val updated = existing.copy(
            status = newStatus,
            resolvedAt = if (newStatus == CaseStatus.RESOLVED) LocalDateTime.now() else existing.resolvedAt,
            closedAt = if (newStatus == CaseStatus.CLOSED) LocalDateTime.now() else existing.closedAt,
            resolutionNotes = resolutionNotes ?: existing.resolutionNotes,
        )

        caseRepository.save(updated)

        // Record status history
        recordStatusChange(caseId, existing.status, newStatus, changedBy, reason)

        return updated
    }

    fun assignCase(
        caseId: String,
        assignedTo: String?,
        assignedTeam: String?,
        assignedBy: String,
    ): CaseRecord {
        val existing = caseRepository.findById(caseId)
            ?: throw IllegalArgumentException("Case not found: $caseId")

        val updated = existing.copy(
            assignedTo = assignedTo,
            assignedTeam = assignedTeam,
            status = if (existing.status == CaseStatus.OPEN) CaseStatus.IN_PROGRESS else existing.status,
        )

        caseRepository.save(updated)

        // Add system note
        addNote(
            caseId = caseId,
            noteText = "Case assigned to ${assignedTo ?: assignedTeam}",
            noteType = NoteType.SYSTEM,
            createdBy = assignedBy,
            customerVisible = false,
        )

        return updated
    }

    fun addNote(
        caseId: String,
        noteText: String,
        noteType: NoteType,
        createdBy: String,
        customerVisible: Boolean,
    ): CaseNote {
        val note = CaseNote(
            noteId = UUID.randomUUID().toString(),
            caseId = caseId,
            noteText = noteText,
            noteType = noteType,
            createdBy = createdBy,
            createdAt = LocalDateTime.now(),
            customerVisible = customerVisible,
        )

        return caseRepository.saveNote(note)
    }

    fun getCaseById(caseId: String): CaseRecord? {
        return caseRepository.findById(caseId)
    }

    fun getCasesByUtilityId(
        utilityId: String,
        status: CaseStatus? = null,
        assignedTo: String? = null,
        limit: Int = 100,
    ): List<CaseRecord> {
        return caseRepository.findByUtilityId(utilityId, status, assignedTo, limit)
    }

    fun getCasesByCustomerId(customerId: String, limit: Int = 50): List<CaseRecord> {
        return caseRepository.findByCustomerId(customerId, limit)
    }

    fun getCaseNotes(caseId: String, customerView: Boolean = false): List<CaseNote> {
        val notes = caseRepository.findNotesByCaseId(caseId)
        return if (customerView) {
            notes.filter { it.customerVisible }
        } else {
            notes
        }
    }

    private fun recordStatusChange(
        caseId: String,
        fromStatus: CaseStatus?,
        toStatus: CaseStatus,
        changedBy: String,
        reason: String?,
    ) {
        val history = CaseStatusHistory(
            historyId = UUID.randomUUID().toString(),
            caseId = caseId,
            fromStatus = fromStatus,
            toStatus = toStatus,
            changedAt = LocalDateTime.now(),
            changedBy = changedBy,
            reason = reason,
        )
        caseRepository.saveStatusHistory(history)
    }

    private fun generateCaseNumber(utilityId: String): String {
        val year = LocalDateTime.now().year
        val sequence = (Math.random() * 999999).toInt().toString().padStart(6, '0')
        return "CASE-$year-$sequence"
    }
}
