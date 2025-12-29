package com.example.usbilling.casemanagement.http

import com.example.usbilling.casemanagement.domain.CaseCategory
import com.example.usbilling.casemanagement.domain.CaseNote
import com.example.usbilling.casemanagement.domain.CasePriority
import com.example.usbilling.casemanagement.domain.CaseRecord
import com.example.usbilling.casemanagement.domain.CaseStatus
import com.example.usbilling.casemanagement.domain.CaseType
import com.example.usbilling.casemanagement.domain.NoteType
import com.example.usbilling.casemanagement.service.CaseService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

/**
 * Internal Case Management API - for CSR and internal use.
 */
@RestController
@RequestMapping("/utilities/{utilityId}/cases")
class CaseController(
    private val caseService: CaseService,
) {

    @PostMapping
    fun createCase(
        @PathVariable utilityId: String,
        @RequestBody @Valid request: CreateCaseRequest,
    ): ResponseEntity<CaseResponse> {
        val caseRecord = caseService.createCase(
            utilityId = utilityId,
            accountId = request.accountId,
            customerId = request.customerId,
            caseType = request.caseType,
            caseCategory = request.caseCategory,
            title = request.title,
            description = request.description,
            priority = request.priority ?: CasePriority.MEDIUM,
            openedBy = request.openedBy,
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(caseRecord.toResponse())
    }

    @GetMapping
    fun listCases(
        @PathVariable utilityId: String,
        @RequestParam(required = false) status: CaseStatus?,
        @RequestParam(required = false) assignedTo: String?,
        @RequestParam(defaultValue = "100") limit: Int,
    ): ResponseEntity<List<CaseResponse>> {
        val cases = caseService.getCasesByUtilityId(utilityId, status, assignedTo, limit)
        return ResponseEntity.ok(cases.map { it.toResponse() })
    }

    @GetMapping("/{caseId}")
    fun getCaseDetail(
        @PathVariable utilityId: String,
        @PathVariable caseId: String,
    ): ResponseEntity<CaseDetailResponse> {
        val caseRecord = caseService.getCaseById(caseId)
            ?: return ResponseEntity.notFound().build()

        if (caseRecord.utilityId != utilityId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        val notes = caseService.getCaseNotes(caseId, customerView = false)

        return ResponseEntity.ok(
            CaseDetailResponse(
                caseRecord = caseRecord.toResponse(),
                notes = notes.map { it.toResponse() },
            ),
        )
    }

    @PutMapping("/{caseId}/status")
    fun updateCaseStatus(
        @PathVariable utilityId: String,
        @PathVariable caseId: String,
        @RequestBody @Valid request: UpdateStatusRequest,
    ): ResponseEntity<CaseResponse> {
        val updated = caseService.updateCaseStatus(
            caseId = caseId,
            newStatus = request.status,
            changedBy = request.changedBy,
            reason = request.reason,
            resolutionNotes = request.resolutionNotes,
        )

        return ResponseEntity.ok(updated.toResponse())
    }

    @PostMapping("/{caseId}/notes")
    fun addNote(
        @PathVariable utilityId: String,
        @PathVariable caseId: String,
        @RequestBody @Valid request: AddNoteRequest,
    ): ResponseEntity<CaseNoteResponse> {
        val note = caseService.addNote(
            caseId = caseId,
            noteText = request.noteText,
            noteType = request.noteType ?: NoteType.INTERNAL,
            createdBy = request.createdBy,
            customerVisible = request.customerVisible ?: false,
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(note.toResponse())
    }

    @PutMapping("/{caseId}/assign")
    fun assignCase(
        @PathVariable utilityId: String,
        @PathVariable caseId: String,
        @RequestBody @Valid request: AssignCaseRequest,
    ): ResponseEntity<CaseResponse> {
        val updated = caseService.assignCase(
            caseId = caseId,
            assignedTo = request.assignedTo,
            assignedTeam = request.assignedTeam,
            assignedBy = request.assignedBy,
        )

        return ResponseEntity.ok(updated.toResponse())
    }
}

// Request DTOs
data class CreateCaseRequest(
    @field:NotBlank val title: String,
    val description: String?,
    val accountId: String?,
    val customerId: String?,
    val caseType: CaseType,
    val caseCategory: CaseCategory,
    val priority: CasePriority?,
    @field:NotBlank val openedBy: String,
)

data class UpdateStatusRequest(
    val status: CaseStatus,
    @field:NotBlank val changedBy: String,
    val reason: String?,
    val resolutionNotes: String?,
)

data class AddNoteRequest(
    @field:NotBlank val noteText: String,
    val noteType: NoteType?,
    @field:NotBlank val createdBy: String,
    val customerVisible: Boolean?,
)

data class AssignCaseRequest(
    val assignedTo: String?,
    val assignedTeam: String?,
    @field:NotBlank val assignedBy: String,
)

// Response DTOs
data class CaseResponse(
    val caseId: String,
    val caseNumber: String,
    val utilityId: String,
    val accountId: String?,
    val customerId: String?,
    val caseType: CaseType,
    val caseCategory: CaseCategory,
    val status: CaseStatus,
    val priority: CasePriority,
    val title: String,
    val description: String?,
    val openedBy: String,
    val openedAt: LocalDateTime,
    val assignedTo: String?,
    val assignedTeam: String?,
    val resolvedAt: LocalDateTime?,
    val closedAt: LocalDateTime?,
    val resolutionNotes: String?,
)

data class CaseNoteResponse(
    val noteId: String,
    val caseId: String,
    val noteText: String,
    val noteType: NoteType,
    val createdBy: String,
    val createdAt: LocalDateTime,
    val customerVisible: Boolean,
)

data class CaseDetailResponse(
    val caseRecord: CaseResponse,
    val notes: List<CaseNoteResponse>,
)

// Extension functions for DTO conversion
fun CaseRecord.toResponse() = CaseResponse(
    caseId = caseId,
    caseNumber = caseNumber,
    utilityId = utilityId,
    accountId = accountId,
    customerId = customerId,
    caseType = caseType,
    caseCategory = caseCategory,
    status = status,
    priority = priority,
    title = title,
    description = description,
    openedBy = openedBy,
    openedAt = openedAt,
    assignedTo = assignedTo,
    assignedTeam = assignedTeam,
    resolvedAt = resolvedAt,
    closedAt = closedAt,
    resolutionNotes = resolutionNotes,
)

fun CaseNote.toResponse() = CaseNoteResponse(
    noteId = noteId,
    caseId = caseId,
    noteText = noteText,
    noteType = noteType,
    createdBy = createdBy,
    createdAt = createdAt,
    customerVisible = customerVisible,
)
