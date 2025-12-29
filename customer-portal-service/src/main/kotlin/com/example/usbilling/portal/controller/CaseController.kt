package com.example.usbilling.portal.controller

import com.example.usbilling.portal.client.CaseManagementClient
import com.example.usbilling.portal.security.CustomerPrincipal
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

/**
 * Customer-facing Case Management API.
 *
 * Allows customers to:
 * - Submit cases (inquiries, complaints, service requests)
 * - View their cases
 * - Add notes to cases
 */
@RestController
@RequestMapping("/api/customers/me/cases")
class CaseController(
    private val caseManagementClient: CaseManagementClient,
) {

    @PostMapping
    fun submitCase(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @RequestBody @Valid request: SubmitCaseRequest,
    ): ResponseEntity<CustomerCaseResponse> {
        // Validate account ownership
        if (!principal.accountIds.contains(request.accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        val caseRecord = caseManagementClient.createCase(
            utilityId = principal.utilityId,
            accountId = request.accountId,
            customerId = principal.customerId,
            caseType = request.caseType,
            caseCategory = request.caseCategory,
            title = request.title,
            description = request.description,
            priority = request.priority,
            openedBy = "customer:${principal.customerId}",
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(caseRecord.toCustomerResponse())
    }

    @GetMapping
    fun listMyCases(
        @AuthenticationPrincipal principal: CustomerPrincipal,
    ): ResponseEntity<List<CustomerCaseResponse>> {
        val cases = caseManagementClient.getCasesByCustomerId(
            utilityId = principal.utilityId,
            customerId = principal.customerId,
        )

        return ResponseEntity.ok(cases.map { it.toCustomerResponse() })
    }

    @GetMapping("/{caseId}")
    fun getCaseDetail(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable caseId: String,
    ): ResponseEntity<CustomerCaseDetailResponse> {
        val caseDetail = caseManagementClient.getCaseDetail(
            utilityId = principal.utilityId,
            caseId = caseId,
        )

        // Verify customer owns this case
        if (caseDetail.caseRecord.customerId != principal.customerId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        return ResponseEntity.ok(caseDetail.toCustomerDetailResponse())
    }

    @PostMapping("/{caseId}/notes")
    fun addNote(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable caseId: String,
        @RequestBody @Valid request: AddCaseNoteRequest,
    ): ResponseEntity<CustomerCaseNoteResponse> {
        // Verify customer owns this case
        val caseRecord = caseManagementClient.getCaseById(
            utilityId = principal.utilityId,
            caseId = caseId,
        )

        if (caseRecord.customerId != principal.customerId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        val note = caseManagementClient.addNote(
            utilityId = principal.utilityId,
            caseId = caseId,
            noteText = request.noteText,
            noteType = "CUSTOMER",
            createdBy = "customer:${principal.customerId}",
            customerVisible = true,
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(note.toCustomerResponse())
    }
}

// Request DTOs
data class SubmitCaseRequest(
    @field:NotBlank val title: String,
    val description: String?,
    @field:NotBlank val accountId: String,
    @field:NotBlank val caseType: String,
    @field:NotBlank val caseCategory: String,
    val priority: String?,
)

data class AddCaseNoteRequest(
    @field:NotBlank val noteText: String,
)

// Response DTOs (customer-friendly, no internal details)
data class CustomerCaseResponse(
    val caseId: String,
    val caseNumber: String,
    val accountId: String?,
    val caseType: String,
    val caseCategory: String,
    val status: String,
    val priority: String,
    val title: String,
    val description: String?,
    val openedAt: LocalDateTime,
    val resolvedAt: LocalDateTime?,
)

data class CustomerCaseNoteResponse(
    val noteId: String,
    val noteText: String,
    val createdAt: LocalDateTime,
    val createdBy: String,
)

data class CustomerCaseDetailResponse(
    val case: CustomerCaseResponse,
    val notes: List<CustomerCaseNoteResponse>,
)

// Extension functions for customer-facing responses
fun CaseResponse.toCustomerResponse() = CustomerCaseResponse(
    caseId = caseId,
    caseNumber = caseNumber,
    accountId = accountId,
    caseType = caseType,
    caseCategory = caseCategory,
    status = status,
    priority = priority,
    title = title,
    description = description,
    openedAt = openedAt,
    resolvedAt = resolvedAt,
)

fun CaseNoteResponse.toCustomerResponse() = CustomerCaseNoteResponse(
    noteId = noteId,
    noteText = noteText,
    createdAt = createdAt,
    createdBy = if (createdBy.startsWith("customer:")) "You" else "Support",
)

fun CaseDetailResponse.toCustomerDetailResponse() = CustomerCaseDetailResponse(
    case = caseRecord.toCustomerResponse(),
    notes = notes.filter { it.customerVisible }.map { it.toCustomerResponse() },
)

// DTOs from case-management-service (client responses)
data class CaseResponse(
    val caseId: String,
    val caseNumber: String,
    val utilityId: String,
    val accountId: String?,
    val customerId: String?,
    val caseType: String,
    val caseCategory: String,
    val status: String,
    val priority: String,
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
    val noteType: String,
    val createdBy: String,
    val createdAt: LocalDateTime,
    val customerVisible: Boolean,
)

data class CaseDetailResponse(
    val caseRecord: CaseResponse,
    val notes: List<CaseNoteResponse>,
)
