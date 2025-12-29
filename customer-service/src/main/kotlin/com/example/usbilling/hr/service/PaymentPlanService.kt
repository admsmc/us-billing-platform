package com.example.usbilling.hr.service

import com.example.usbilling.hr.domain.*
import com.example.usbilling.hr.repository.PaymentPlanRepository
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*

@Service
class PaymentPlanService(
    private val paymentPlanRepository: PaymentPlanRepository,
) {

    companion object {
        private val END_OF_TIME = LocalDateTime.parse("9999-12-31T23:59:59")
    }

    /**
     * Create a new payment plan.
     */
    fun createPaymentPlan(
        utilityId: String,
        customerId: String,
        accountId: String,
        planType: PaymentPlanType,
        totalAmountCents: Long,
        downPaymentCents: Long,
        installmentCount: Int,
        paymentFrequency: PaymentFrequency,
        startDate: LocalDate,
        maxMissedPayments: Int = 2,
        createdBy: String,
    ): PaymentPlan {
        require(totalAmountCents > 0) { "Total amount must be positive" }
        require(downPaymentCents >= 0) { "Down payment cannot be negative" }
        require(downPaymentCents < totalAmountCents) { "Down payment must be less than total amount" }
        require(installmentCount > 0) { "Installment count must be positive" }

        val remainingBalance = totalAmountCents - downPaymentCents
        val installmentAmount = remainingBalance / installmentCount

        val firstPaymentDate = calculateNextPaymentDate(startDate, paymentFrequency)
        val finalPaymentDate = calculateFinalPaymentDate(firstPaymentDate, installmentCount, paymentFrequency)

        val now = LocalDateTime.now()
        val plan = PaymentPlan(
            planId = UUID.randomUUID().toString(),
            utilityId = utilityId,
            customerId = customerId,
            accountId = accountId,
            planType = planType,
            status = PaymentPlanStatus.ACTIVE,
            totalAmountCents = totalAmountCents,
            downPaymentCents = downPaymentCents,
            remainingBalanceCents = remainingBalance,
            installmentAmountCents = installmentAmount,
            installmentCount = installmentCount,
            installmentsPaid = 0,
            paymentFrequency = paymentFrequency,
            startDate = startDate,
            firstPaymentDate = firstPaymentDate,
            finalPaymentDate = finalPaymentDate,
            missedPayments = 0,
            maxMissedPayments = maxMissedPayments,
            systemFrom = now,
            systemTo = END_OF_TIME,
            modifiedBy = createdBy,
            createdAt = now,
            createdBy = createdBy,
            cancelledAt = null,
            cancelledReason = null,
        )

        val savedPlan = paymentPlanRepository.savePlan(plan)

        // Generate installments
        generateInstallments(savedPlan, createdBy)

        return savedPlan
    }

    /**
     * Generate installments for a payment plan.
     */
    private fun generateInstallments(plan: PaymentPlan, createdBy: String) {
        var currentDueDate = plan.firstPaymentDate
        val now = LocalDateTime.now()

        for (i in 1..plan.installmentCount) {
            val installmentAmount = if (i == plan.installmentCount) {
                // Last installment gets any remainder
                plan.remainingBalanceCents - (plan.installmentAmountCents * (plan.installmentCount - 1))
            } else {
                plan.installmentAmountCents
            }

            val installment = PaymentPlanInstallment(
                installmentId = UUID.randomUUID().toString(),
                planId = plan.planId,
                installmentNumber = i,
                dueDate = currentDueDate,
                amountCents = installmentAmount,
                paidAmountCents = 0,
                status = InstallmentStatus.PENDING,
                paidAt = null,
                systemFrom = now,
                systemTo = END_OF_TIME,
                modifiedBy = createdBy,
            )

            paymentPlanRepository.saveInstallment(installment)

            currentDueDate = calculateNextPaymentDate(currentDueDate, plan.paymentFrequency)
        }
    }

    /**
     * Apply a payment to a payment plan.
     */
    fun applyPayment(
        planId: String,
        paymentId: String,
        amountCents: Long,
        appliedBy: String,
    ): PaymentPlan {
        require(amountCents > 0) { "Payment amount must be positive" }

        val plan = paymentPlanRepository.findById(planId)
            ?: throw IllegalArgumentException("Payment plan not found: $planId")

        if (plan.status != PaymentPlanStatus.ACTIVE) {
            throw IllegalStateException("Cannot apply payment to non-active plan")
        }

        val installments = paymentPlanRepository.findInstallmentsByPlanId(planId)
            .sortedBy { it.installmentNumber }

        var remainingPayment = amountCents

        for (installment in installments) {
            if (remainingPayment <= 0) break
            if (installment.status == InstallmentStatus.PAID) continue

            val amountDue = installment.amountCents - installment.paidAmountCents
            val amountToApply = minOf(remainingPayment, amountDue)

            val newPaidAmount = installment.paidAmountCents + amountToApply
            val newStatus = if (newPaidAmount >= installment.amountCents) {
                InstallmentStatus.PAID
            } else {
                InstallmentStatus.PARTIAL
            }

            val now = LocalDateTime.now()
            val updatedInstallment = installment.copy(
                paidAmountCents = newPaidAmount,
                status = newStatus,
                paidAt = if (newStatus == InstallmentStatus.PAID) now else null,
                systemFrom = now,
                modifiedBy = appliedBy,
            )

            paymentPlanRepository.saveInstallment(updatedInstallment)

            // Record payment application
            val payment = PaymentPlanPayment(
                id = UUID.randomUUID().toString(),
                planId = planId,
                installmentId = installment.installmentId,
                paymentId = paymentId,
                amountCents = amountToApply,
                appliedAt = now,
            )
            paymentPlanRepository.savePayment(payment)

            remainingPayment -= amountToApply
        }

        // Update plan status
        return updatePlanStatus(planId, appliedBy)
    }

    /**
     * Mark an installment as missed.
     */
    fun markInstallmentMissed(planId: String, installmentId: String, markedBy: String): PaymentPlan {
        val plan = paymentPlanRepository.findById(planId)
            ?: throw IllegalArgumentException("Payment plan not found: $planId")

        val installment = paymentPlanRepository.findInstallmentsByPlanId(planId)
            .find { it.installmentId == installmentId }
            ?: throw IllegalArgumentException("Installment not found: $installmentId")

        if (installment.status != InstallmentStatus.PENDING) {
            throw IllegalStateException("Cannot mark non-pending installment as missed")
        }

        val now = LocalDateTime.now()
        val updatedInstallment = installment.copy(
            status = InstallmentStatus.MISSED,
            systemFrom = now,
            modifiedBy = markedBy,
        )

        paymentPlanRepository.saveInstallment(updatedInstallment)

        val newMissedPayments = plan.missedPayments + 1
        val newStatus = if (newMissedPayments >= plan.maxMissedPayments) {
            PaymentPlanStatus.BROKEN
        } else {
            plan.status
        }

        val updatedPlan = plan.copy(
            missedPayments = newMissedPayments,
            status = newStatus,
            systemFrom = now,
            modifiedBy = markedBy,
        )

        return paymentPlanRepository.savePlan(updatedPlan)
    }

    /**
     * Cancel a payment plan.
     */
    fun cancelPaymentPlan(planId: String, reason: String, cancelledBy: String): PaymentPlan {
        val plan = paymentPlanRepository.findById(planId)
            ?: throw IllegalArgumentException("Payment plan not found: $planId")

        if (plan.status == PaymentPlanStatus.COMPLETED || plan.status == PaymentPlanStatus.CANCELLED) {
            throw IllegalStateException("Cannot cancel plan in status: ${plan.status}")
        }

        val now = LocalDateTime.now()
        val updatedPlan = plan.copy(
            status = PaymentPlanStatus.CANCELLED,
            cancelledAt = now,
            cancelledReason = reason,
            systemFrom = now,
            modifiedBy = cancelledBy,
        )

        return paymentPlanRepository.savePlan(updatedPlan)
    }

    /**
     * Get payment plan with installments.
     */
    fun getPaymentPlanWithInstallments(planId: String): PaymentPlanWithInstallments? {
        val plan = paymentPlanRepository.findById(planId) ?: return null
        val installments = paymentPlanRepository.findInstallmentsByPlanId(planId)
        return PaymentPlanWithInstallments(plan, installments)
    }

    /**
     * Get customer's payment plans.
     */
    fun getCustomerPaymentPlans(customerId: String, status: PaymentPlanStatus? = null): List<PaymentPlan> = paymentPlanRepository.findByCustomerId(customerId, status)

    /**
     * Check payment plan eligibility for a customer.
     */
    fun checkEligibility(customerId: String, totalAmountCents: Long): PaymentPlanEligibility {
        val activePlans = paymentPlanRepository.findByCustomerId(customerId, PaymentPlanStatus.ACTIVE)
        val brokenPlans = paymentPlanRepository.findByCustomerId(customerId, PaymentPlanStatus.BROKEN)

        val hasActivePlan = activePlans.isNotEmpty()
        val hasBrokenPlan = brokenPlans.isNotEmpty()
        val meetsMinimumAmount = totalAmountCents >= 5000 // $50 minimum

        val eligible = !hasActivePlan && !hasBrokenPlan && meetsMinimumAmount

        val reasons = mutableListOf<String>()
        if (hasActivePlan) reasons.add("Customer already has an active payment plan")
        if (hasBrokenPlan) reasons.add("Customer has a broken payment plan")
        if (!meetsMinimumAmount) reasons.add("Amount does not meet minimum threshold")

        return PaymentPlanEligibility(
            eligible = eligible,
            reasons = reasons,
        )
    }

    /**
     * Update plan status based on current installment state.
     */
    private fun updatePlanStatus(planId: String, modifiedBy: String): PaymentPlan {
        val plan = paymentPlanRepository.findById(planId)
            ?: throw IllegalArgumentException("Payment plan not found: $planId")

        val installments = paymentPlanRepository.findInstallmentsByPlanId(planId)
        val allPaid = installments.all { it.status == InstallmentStatus.PAID }

        if (allPaid) {
            val now = LocalDateTime.now()
            val updatedPlan = plan.copy(
                status = PaymentPlanStatus.COMPLETED,
                installmentsPaid = installments.size,
                systemFrom = now,
                modifiedBy = modifiedBy,
            )
            return paymentPlanRepository.savePlan(updatedPlan)
        }

        val paidCount = installments.count { it.status == InstallmentStatus.PAID }
        if (paidCount != plan.installmentsPaid) {
            val now = LocalDateTime.now()
            val updatedPlan = plan.copy(
                installmentsPaid = paidCount,
                systemFrom = now,
                modifiedBy = modifiedBy,
            )
            return paymentPlanRepository.savePlan(updatedPlan)
        }

        return plan
    }

    private fun calculateNextPaymentDate(fromDate: LocalDate, frequency: PaymentFrequency): LocalDate = when (frequency) {
        PaymentFrequency.WEEKLY -> fromDate.plusWeeks(1)
        PaymentFrequency.BIWEEKLY -> fromDate.plusWeeks(2)
        PaymentFrequency.MONTHLY -> fromDate.plusMonths(1)
    }

    private fun calculateFinalPaymentDate(
        firstPaymentDate: LocalDate,
        installmentCount: Int,
        frequency: PaymentFrequency,
    ): LocalDate {
        var date = firstPaymentDate
        repeat(installmentCount - 1) {
            date = calculateNextPaymentDate(date, frequency)
        }
        return date
    }
}

data class PaymentPlanWithInstallments(
    val plan: PaymentPlan,
    val installments: List<PaymentPlanInstallment>,
)

data class PaymentPlanEligibility(
    val eligible: Boolean,
    val reasons: List<String>,
)
