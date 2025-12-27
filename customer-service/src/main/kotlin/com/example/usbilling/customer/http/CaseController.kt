package com.example.usbilling.customer.http

import com.example.usbilling.customer.api.*
import com.example.usbilling.customer.repository.JdbcCaseRepository
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/utilities/{utilityId}/cases")
class CaseController(
    private val caseService: CaseManagementService,
    private val caseRepository: JdbcCaseRepository,
) {
    
    /**
     * Create a new case.
     * POST /api/v1/utilities/{utilityId}/cases
     */
    @PostMapping
    fun createCase(
        @PathVariable utilityId: String,
        @RequestBody request: CreateCaseRequest,
        @RequestHeader("X-User-Id", defaultValue = "system") userId: String,
    ): ResponseEntity<CaseRecordDto> {
        val case = caseService.createCase(request, userId)
        return ResponseEntity.status(HttpStatus.CREATED).body(case)
    }
    
    /**
     * Get case details.
     * GET /api/v1/utilities/{utilityId}/cases/{caseId}
     */
    @GetMapping("/{caseId}")
    fun getCase(
        @PathVariable utilityId: String,
        @PathVariable caseId: String,
    ): ResponseEntity<CaseRecordDto> {
        val case = caseRepository.findById(caseId)
            ?: return ResponseEntity.notFound().build()
        
        return ResponseEntity.ok(toDto(case))
    }
    
    /**
     * Search cases.
     * GET /api/v1/utilities/{utilityId}/cases?status=...&priority=...
     */
    @GetMapping
    fun searchCases(
        @PathVariable utilityId: String,
        @RequestParam(required = false) accountId: String?,
        @RequestParam(required = false) customerId: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) priority: String?,
        @RequestParam(required = false) assignedTo: String?,
        @RequestParam(required = false) assignedTeam: String?,
        @RequestParam(defaultValue = "100") limit: Int,
    ): ResponseEntity<List<CaseRecordDto>> {
        val cases = caseRepository.searchCases(
            utilityId = UtilityId(utilityId),
            accountId = accountId,
            customerId = customerId?.let { CustomerId(it) },
            status = status?.let { com.example.usbilling.customer.model.CaseStatus.valueOf(it) },
            priority = priority?.let { com.example.usbilling.customer.model.CasePriority.valueOf(it) },
            assignedTo = assignedTo,
            assignedTeam = assignedTeam,
            limit = limit,
        )
        
        return ResponseEntity.ok(cases.map { toDto(it) })
    }
    
    /**
     * Update case status.
     * PATCH /api/v1/utilities/{utilityId}/cases/{caseId}/status
     */
    @PatchMapping("/{caseId}/status")
    fun updateStatus(
        @PathVariable utilityId: String,
        @PathVariable caseId: String,
        @RequestBody body: Map<String, String>,
        @RequestHeader("X-User-Id", defaultValue = "system") userId: String,
    ): ResponseEntity<CaseRecordDto> {
        val status = body["status"] ?: throw IllegalArgumentException("status is required")
        val reason = body["reason"]
        val notes = body["notes"]
        
        val case = caseService.updateCaseStatus(caseId, status, userId, reason, notes)
        return ResponseEntity.ok(case)
    }
    
    /**
     * Assign case.
     * POST /api/v1/utilities/{utilityId}/cases/{caseId}/assign
     */
    @PostMapping("/{caseId}/assign")
    fun assignCase(
        @PathVariable utilityId: String,
        @PathVariable caseId: String,
        @RequestBody body: Map<String, String?>,
        @RequestHeader("X-User-Id", defaultValue = "system") userId: String,
    ): ResponseEntity<CaseRecordDto> {
        val assignedTo = body["assignedTo"]
        val assignedTeam = body["assignedTeam"]
        
        val case = caseService.assignCase(caseId, assignedTo, assignedTeam, userId)
        return ResponseEntity.ok(case)
    }
    
    /**
     * Add case note.
     * POST /api/v1/utilities/{utilityId}/cases/{caseId}/notes
     */
    @PostMapping("/{caseId}/notes")
    fun addNote(
        @PathVariable utilityId: String,
        @PathVariable caseId: String,
        @RequestBody note: AddCaseNoteRequest,
        @RequestHeader("X-User-Id", defaultValue = "system") userId: String,
    ): ResponseEntity<CaseNoteDto> {
        val created = caseService.addCaseNote(caseId, note, userId)
        return ResponseEntity.status(HttpStatus.CREATED).body(created)
    }
    
    /**
     * Get case notes.
     * GET /api/v1/utilities/{utilityId}/cases/{caseId}/notes
     */
    @GetMapping("/{caseId}/notes")
    fun getNotes(
        @PathVariable utilityId: String,
        @PathVariable caseId: String,
        @RequestParam(defaultValue = "100") limit: Int,
    ): ResponseEntity<List<CaseNoteDto>> {
        val notes = caseRepository.getNotes(caseId, limit)
        return ResponseEntity.ok(notes.map { toNoteDto(it) })
    }
    
    /**
     * Close case.
     * POST /api/v1/utilities/{utilityId}/cases/{caseId}/close
     */
    @PostMapping("/{caseId}/close")
    fun closeCase(
        @PathVariable utilityId: String,
        @PathVariable caseId: String,
        @RequestBody body: Map<String, String>,
        @RequestHeader("X-User-Id", defaultValue = "system") userId: String,
    ): ResponseEntity<CaseRecordDto> {
        val resolutionNotes = body["resolutionNotes"] ?: throw IllegalArgumentException("resolutionNotes is required")
        val case = caseService.closeCase(caseId, userId, resolutionNotes)
        return ResponseEntity.ok(case)
    }
    
    private fun toDto(case: com.example.usbilling.customer.model.CaseRecord): CaseRecordDto {
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
    
    private fun toNoteDto(note: com.example.usbilling.customer.model.CaseNote): CaseNoteDto {
        return CaseNoteDto(
            noteId = note.noteId,
            caseId = note.caseId,
            noteType = note.noteType.name,
            content = note.content,
            isInternal = note.isInternal,
            createdBy = note.createdBy,
            createdAt = note.createdAt,
        )
    }
}
