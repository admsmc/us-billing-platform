package com.example.usbilling.csrportal.controller

import com.example.usbilling.csrportal.service.ActionResult
import com.example.usbilling.csrportal.service.QuickActionService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/csr/customers/{customerId}/actions")
class QuickActionsController(
    private val quickActionService: QuickActionService,
) {

    /**
     * Apply a one-time credit to customer's account.
     */
    @PostMapping("/apply-credit")
    fun applyCredit(
        @PathVariable customerId: String,
        @RequestBody @Valid request: ApplyCreditRequest,
        @RequestHeader("X-CSR-Id") csrId: String,
    ): ResponseEntity<ActionResult> {
        val result = quickActionService.applyCredit(
            customerId = customerId,
            utilityId = request.utilityId,
            amountCents = request.amountCents,
            reason = request.reason,
            csrId = csrId,
        )
        return ResponseEntity.ok(result)
    }

    /**
     * Waive late fee for a bill.
     */
    @PostMapping("/waive-late-fee")
    fun waiveLateFee(
        @PathVariable customerId: String,
        @RequestBody @Valid request: WaiveLateFeeRequest,
        @RequestHeader("X-CSR-Id") csrId: String,
    ): ResponseEntity<ActionResult> {
        val result = quickActionService.waiveLateFee(
            customerId = customerId,
            utilityId = request.utilityId,
            billId = request.billId,
            reason = request.reason,
            csrId = csrId,
        )
        return ResponseEntity.ok(result)
    }

    /**
     * Send email to customer.
     */
    @PostMapping("/send-email")
    fun sendEmail(
        @PathVariable customerId: String,
        @RequestBody @Valid request: SendEmailRequest,
        @RequestHeader("X-CSR-Id") csrId: String,
    ): ResponseEntity<ActionResult> {
        val result = quickActionService.sendEmail(
            customerId = customerId,
            utilityId = request.utilityId,
            subject = request.subject,
            body = request.body,
            csrId = csrId,
        )
        return ResponseEntity.ok(result)
    }

    /**
     * Create a case on behalf of customer.
     */
    @PostMapping("/create-case")
    fun createCase(
        @PathVariable customerId: String,
        @RequestBody @Valid request: CreateCaseRequest,
        @RequestHeader("X-CSR-Id") csrId: String,
    ): ResponseEntity<ActionResult> {
        val result = quickActionService.createCase(
            customerId = customerId,
            utilityId = request.utilityId,
            accountId = request.accountId,
            caseType = request.caseType,
            caseCategory = request.caseCategory,
            title = request.title,
            description = request.description,
            priority = request.priority,
            csrId = csrId,
        )
        return ResponseEntity.ok(result)
    }

    /**
     * Extend payment due date for a bill.
     */
    @PostMapping("/extend-due-date")
    fun extendDueDate(
        @PathVariable customerId: String,
        @RequestBody @Valid request: ExtendDueDateRequest,
        @RequestHeader("X-CSR-Id") csrId: String,
    ): ResponseEntity<ActionResult> {
        val result = quickActionService.extendDueDate(
            customerId = customerId,
            utilityId = request.utilityId,
            billId = request.billId,
            newDueDate = request.newDueDate,
            reason = request.reason,
            csrId = csrId,
        )
        return ResponseEntity.ok(result)
    }
}

// Request DTOs

data class ApplyCreditRequest(
    val utilityId: String,
    val amountCents: Long,
    val reason: String,
)

data class WaiveLateFeeRequest(
    val utilityId: String,
    val billId: String,
    val reason: String,
)

data class SendEmailRequest(
    val utilityId: String,
    val subject: String,
    val body: String,
)

data class CreateCaseRequest(
    val utilityId: String,
    val accountId: String,
    val caseType: String,
    val caseCategory: String,
    val title: String,
    val description: String,
    val priority: String,
)

data class ExtendDueDateRequest(
    val utilityId: String,
    val billId: String,
    val newDueDate: String,
    val reason: String,
)
