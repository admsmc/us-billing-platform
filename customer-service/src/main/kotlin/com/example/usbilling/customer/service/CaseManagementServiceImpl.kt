package com.example.usbilling.customer.service

import com.example.usbilling.customer.api.*
import com.example.usbilling.customer.model.*
import com.example.usbilling.customer.repository.JdbcCaseRepository
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@Service
class CaseManagementServiceImpl(
    private val caseRepository: JdbcCaseRepository,
) : CaseManagementService {
    
    private val caseNumberSequence = AtomicInteger(1000)
    
    @Transactional
    override fun createCase(request: CreateCaseRequest, createdBy: String): CaseRecordDto {
        val caseId = "case-${UUID.randomUUID()}"
        val caseNumber = "CASE-${System.currentTimeMillis()}-${caseNumberSequence.incrementAndGet()}"
        val now = Instant.now()
        
        val caseRecord = CaseRecord(
            caseId = caseId,
            caseNumber = caseNumber,
            utilityId = UtilityId(request.utilityId),
            accountId = request.accountId,
            customerId = request.customerId?.let { CustomerId(it) },
            caseType = CaseType.valueOf(request.caseType),
            caseCategory = CaseCategory.valueOf(request.caseCategory),
            status = CaseStatus.OPEN,
            priority = CasePriority.valueOf(request.priority),
            severity = null,
            title = request.title,
            description = request.description,
            resolutionNotes = null,
            rootCause = null,
            preventativeAction = null,
            assignedTo = null,
            assignedTeam = null,
            openedBy = createdBy,
            closedBy = null,
            estimatedResolutionDate = null,
            createdAt = now,
            updatedAt = now,
            closedAt = null,
            tags = emptyList(),
            relatedCaseIds = emptyList(),
        )
        
        val created = caseRepository.insert(caseRecord)
        
        // Record initial status
        caseRepository.addStatusHistory(
            CaseStatusHistory(
                historyId = "history-${UUID.randomUUID()}",
                caseId = caseId,
                fromStatus = null,
                toStatus = CaseStatus.OPEN,
                changedBy = createdBy,
                changedAt = now,
                reason = "Case created",
                notes = null,
            )
        )
        
        return toDto(created)
    }
    
    @Transactional
    override fun updateCaseStatus(
        caseId: String,
        newStatus: String,
        updatedBy: String,
        reason: String?,
        notes: String?
    ): CaseRecordDto {
        val case = caseRepository.findById(caseId)
            ?: throw IllegalArgumentException("Case $caseId not found")
        
        val targetStatus = CaseStatus.valueOf(newStatus)
        val now = Instant.now()
        
        // Update case
        val updated = case.copy(
            status = targetStatus,
            updatedAt = now,
            closedAt = if (targetStatus == CaseStatus.CLOSED || targetStatus == CaseStatus.RESOLVED) now else case.closedAt,
            closedBy = if (targetStatus == CaseStatus.CLOSED || targetStatus == CaseStatus.RESOLVED) updatedBy else case.closedBy,
        )
        
        caseRepository.update(updated)
        
        // Record status change
        caseRepository.addStatusHistory(
            CaseStatusHistory(
                historyId = "history-${UUID.randomUUID()}",
                caseId = caseId,
                fromStatus = case.status,
                toStatus = targetStatus,
                changedBy = updatedBy,
                changedAt = now,
                reason = reason,
                notes = notes,
            )
        )
        
        return toDto(updated)
    }
    
    @Transactional
    override fun assignCase(
        caseId: String,
        assignedTo: String?,
        assignedTeam: String?,
        assignedBy: String
    ): CaseRecordDto {
        val case = caseRepository.findById(caseId)
            ?: throw IllegalArgumentException("Case $caseId not found")
        
        val updated = case.copy(
            assignedTo = assignedTo,
            assignedTeam = assignedTeam,
            updatedAt = Instant.now(),
        )
        
        caseRepository.update(updated)
        
        // If assigning to someone, update status to IN_PROGRESS if still OPEN
        if ((assignedTo != null || assignedTeam != null) && case.status == CaseStatus.OPEN) {
            updateCaseStatus(caseId, CaseStatus.IN_PROGRESS.name, assignedBy, "Assigned to ${assignedTo ?: assignedTeam}", null)
        }
        
        return toDto(updated)
    }
    
    @Transactional
    override fun addCaseNote(caseId: String, note: AddCaseNoteRequest, createdBy: String): CaseNoteDto {
        val case = caseRepository.findById(caseId)
            ?: throw IllegalArgumentException("Case $caseId not found")
        
        val noteId = "note-${UUID.randomUUID()}"
        val now = Instant.now()
        
        val caseNote = CaseNote(
            noteId = noteId,
            caseId = caseId,
            noteType = CaseNoteType.valueOf(note.noteType),
            content = note.content,
            isInternal = note.isInternal,
            createdBy = createdBy,
            createdAt = now,
            attachmentIds = emptyList(),
        )
        
        caseRepository.addNote(caseNote)
        
        // Update case timestamp
        caseRepository.update(case.copy(updatedAt = now))
        
        return CaseNoteDto(
            noteId = caseNote.noteId,
            caseId = caseNote.caseId,
            noteType = caseNote.noteType.name,
            content = caseNote.content,
            isInternal = caseNote.isInternal,
            createdBy = caseNote.createdBy,
            createdAt = caseNote.createdAt,
        )
    }
    
    @Transactional
    override fun closeCase(caseId: String, closedBy: String, resolutionNotes: String): CaseRecordDto {
        val case = caseRepository.findById(caseId)
            ?: throw IllegalArgumentException("Case $caseId not found")
        
        val now = Instant.now()
        
        val updated = case.copy(
            status = CaseStatus.CLOSED,
            resolutionNotes = resolutionNotes,
            closedBy = closedBy,
            closedAt = now,
            updatedAt = now,
        )
        
        caseRepository.update(updated)
        
        // Record closure
        caseRepository.addStatusHistory(
            CaseStatusHistory(
                historyId = "history-${UUID.randomUUID()}",
                caseId = caseId,
                fromStatus = case.status,
                toStatus = CaseStatus.CLOSED,
                changedBy = closedBy,
                changedAt = now,
                reason = "Case closed",
                notes = resolutionNotes,
            )
        )
        
        return toDto(updated)
    }
    
    private fun toDto(case: CaseRecord): CaseRecordDto {
        return CaseRecordDto(
            caseId = case.caseId,
            caseNumber = case.caseNumber,
            utilityId = case.utilityId.value,
            accountId = case.accountId,
            customerId = case.customerId?.value,
            caseType = case.caseType.name,
            caseCategory = case.caseCategory.name,
            status = case.status.name,
            priority = case.priority.name,
            title = case.title,
            description = case.description,
            assignedTo = case.assignedTo,
            assignedTeam = case.assignedTeam,
            createdAt = case.createdAt,
            openedBy = case.openedBy,
        )
    }
}
