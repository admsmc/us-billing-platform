package com.example.usbilling.hr.controller

import com.example.usbilling.hr.domain.*
import com.example.usbilling.hr.service.PaymentPlanEligibility
import com.example.usbilling.hr.service.PaymentPlanService
import com.example.usbilling.hr.service.PaymentPlanWithInstallments
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.time.LocalDateTime

@RestController
@RequestMapping("/utilities/{utilityId}/payment-plans")
class PaymentPlanController(
    private val paymentPlanService: PaymentPlanService,
) {

    /**
     * Create a new payment plan (CSR operation).
     */
    @PostMapping
    fun createPaymentPlan(
        @PathVariable utilityId: String,
        @RequestBody request: CreatePaymentPlanRequest,
        @RequestHeader("X-User-Id") userId: String,
    ): ResponseEntity<PaymentPlanResponse> {
        val plan = paymentPlanService.createPaymentPlan(
            utilityId = utilityId,
            customerId = request.customerId,
            accountId = request.accountId,
            planType = request.planType,
            totalAmountCents = request.totalAmountCents,
            downPaymentCents = request.downPaymentCents,
            installmentCount = request.installmentCount,
            paymentFrequency = request.paymentFrequency,
            startDate = request.startDate,
            maxMissedPayments = request.maxMissedPayments ?: 2,
            createdBy = userId,
        )

        return ResponseEntity.ok(plan.toResponse())
    }

    /**
     * Get payment plan with installments.
     */
    @GetMapping("/{planId}")
    fun getPaymentPlan(
        @PathVariable utilityId: String,
        @PathVariable planId: String,
    ): ResponseEntity<PaymentPlanWithInstallmentsResponse> {
        val result = paymentPlanService.getPaymentPlanWithInstallments(planId)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(result.toResponse())
    }

    /**
     * Get customer's payment plans.
     */
    @GetMapping("/customers/{customerId}")
    fun getCustomerPaymentPlans(
        @PathVariable utilityId: String,
        @PathVariable customerId: String,
        @RequestParam(required = false) status: PaymentPlanStatus?,
    ): ResponseEntity<List<PaymentPlanResponse>> {
        val plans = paymentPlanService.getCustomerPaymentPlans(customerId, status)
        return ResponseEntity.ok(plans.map { it.toResponse() })
    }

    /**
     * Apply a payment to a payment plan.
     */
    @PostMapping("/{planId}/payments")
    fun applyPayment(
        @PathVariable utilityId: String,
        @PathVariable planId: String,
        @RequestBody request: ApplyPaymentRequest,
        @RequestHeader("X-User-Id") userId: String,
    ): ResponseEntity<PaymentPlanResponse> {
        val plan = paymentPlanService.applyPayment(
            planId = planId,
            paymentId = request.paymentId,
            amountCents = request.amountCents,
            appliedBy = userId,
        )

        return ResponseEntity.ok(plan.toResponse())
    }

    /**
     * Mark an installment as missed.
     */
    @PostMapping("/{planId}/installments/{installmentId}/mark-missed")
    fun markInstallmentMissed(
        @PathVariable utilityId: String,
        @PathVariable planId: String,
        @PathVariable installmentId: String,
        @RequestHeader("X-User-Id") userId: String,
    ): ResponseEntity<PaymentPlanResponse> {
        val plan = paymentPlanService.markInstallmentMissed(
            planId = planId,
            installmentId = installmentId,
            markedBy = userId,
        )

        return ResponseEntity.ok(plan.toResponse())
    }

    /**
     * Cancel a payment plan.
     */
    @PostMapping("/{planId}/cancel")
    fun cancelPaymentPlan(
        @PathVariable utilityId: String,
        @PathVariable planId: String,
        @RequestBody request: CancelPaymentPlanRequest,
        @RequestHeader("X-User-Id") userId: String,
    ): ResponseEntity<PaymentPlanResponse> {
        val plan = paymentPlanService.cancelPaymentPlan(
            planId = planId,
            reason = request.reason,
            cancelledBy = userId,
        )

        return ResponseEntity.ok(plan.toResponse())
    }

    /**
     * Check payment plan eligibility for a customer.
     */
    @GetMapping("/customers/{customerId}/eligibility")
    fun checkEligibility(
        @PathVariable utilityId: String,
        @PathVariable customerId: String,
        @RequestParam totalAmountCents: Long,
    ): ResponseEntity<PaymentPlanEligibility> {
        val eligibility = paymentPlanService.checkEligibility(customerId, totalAmountCents)
        return ResponseEntity.ok(eligibility)
    }
}

// Request/Response DTOs

data class CreatePaymentPlanRequest(
    val customerId: String,
    val accountId: String,
    val planType: PaymentPlanType,
    val totalAmountCents: Long,
    val downPaymentCents: Long,
    val installmentCount: Int,
    val paymentFrequency: PaymentFrequency,
    val startDate: LocalDate,
    val maxMissedPayments: Int?,
)

data class ApplyPaymentRequest(
    val paymentId: String,
    val amountCents: Long,
)

data class CancelPaymentPlanRequest(
    val reason: String,
)

data class PaymentPlanResponse(
    val planId: String,
    val utilityId: String,
    val customerId: String,
    val accountId: String,
    val planType: PaymentPlanType,
    val status: PaymentPlanStatus,
    val totalAmountCents: Long,
    val downPaymentCents: Long,
    val remainingBalanceCents: Long,
    val installmentAmountCents: Long,
    val installmentCount: Int,
    val installmentsPaid: Int,
    val paymentFrequency: PaymentFrequency,
    val startDate: LocalDate,
    val firstPaymentDate: LocalDate,
    val finalPaymentDate: LocalDate,
    val missedPayments: Int,
    val maxMissedPayments: Int,
    val createdAt: LocalDateTime,
    val createdBy: String,
    val cancelledAt: LocalDateTime?,
    val cancelledReason: String?,
)

data class InstallmentResponse(
    val installmentId: String,
    val planId: String,
    val installmentNumber: Int,
    val dueDate: LocalDate,
    val amountCents: Long,
    val paidAmountCents: Long,
    val status: InstallmentStatus,
    val paidAt: LocalDateTime?,
)

data class PaymentPlanWithInstallmentsResponse(
    val plan: PaymentPlanResponse,
    val installments: List<InstallmentResponse>,
)

// Extension functions for mapping

private fun PaymentPlan.toResponse() = PaymentPlanResponse(
    planId = planId,
    utilityId = utilityId,
    customerId = customerId,
    accountId = accountId,
    planType = planType,
    status = status,
    totalAmountCents = totalAmountCents,
    downPaymentCents = downPaymentCents,
    remainingBalanceCents = remainingBalanceCents,
    installmentAmountCents = installmentAmountCents,
    installmentCount = installmentCount,
    installmentsPaid = installmentsPaid,
    paymentFrequency = paymentFrequency,
    startDate = startDate,
    firstPaymentDate = firstPaymentDate,
    finalPaymentDate = finalPaymentDate,
    missedPayments = missedPayments,
    maxMissedPayments = maxMissedPayments,
    createdAt = createdAt,
    createdBy = createdBy,
    cancelledAt = cancelledAt,
    cancelledReason = cancelledReason,
)

private fun PaymentPlanInstallment.toResponse() = InstallmentResponse(
    installmentId = installmentId,
    planId = planId,
    installmentNumber = installmentNumber,
    dueDate = dueDate,
    amountCents = amountCents,
    paidAmountCents = paidAmountCents,
    status = status,
    paidAt = paidAt,
)

private fun PaymentPlanWithInstallments.toResponse() = PaymentPlanWithInstallmentsResponse(
    plan = plan.toResponse(),
    installments = installments.map { it.toResponse() },
)
