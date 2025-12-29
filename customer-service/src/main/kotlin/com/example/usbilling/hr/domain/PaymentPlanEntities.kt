package com.example.usbilling.hr.domain

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Payment plan - allows customers to spread payments over time.
 * Bitemporal SCD2: All changes create new rows.
 */
data class PaymentPlan(
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
    // Bitemporal SCD2 fields
    val systemFrom: LocalDateTime,
    val systemTo: LocalDateTime,
    val modifiedBy: String,
    // Audit
    val createdAt: LocalDateTime,
    val createdBy: String,
    val cancelledAt: LocalDateTime?,
    val cancelledReason: String?,
)

/**
 * Payment plan installment.
 * Bitemporal SCD2: All payment updates create new rows.
 */
data class PaymentPlanInstallment(
    val installmentId: String,
    val planId: String,
    val installmentNumber: Int,
    val dueDate: LocalDate,
    val amountCents: Long,
    val paidAmountCents: Long,
    val status: InstallmentStatus,
    val paidAt: LocalDateTime?,
    // Bitemporal SCD2 fields
    val systemFrom: LocalDateTime,
    val systemTo: LocalDateTime,
    val modifiedBy: String,
)

/**
 * Link between payment and installment
 */
data class PaymentPlanPayment(
    val id: String,
    val planId: String,
    val installmentId: String,
    val paymentId: String, // From payments-service
    val amountCents: Long,
    val appliedAt: LocalDateTime,
)

enum class PaymentPlanType {
    STANDARD,
    HARDSHIP,
    BUDGET_BILLING,
}

enum class PaymentPlanStatus {
    ACTIVE,
    COMPLETED,
    BROKEN,
    CANCELLED,
}

enum class PaymentFrequency {
    WEEKLY,
    BIWEEKLY,
    MONTHLY,
}

enum class InstallmentStatus {
    PENDING,
    PAID,
    PARTIAL,
    MISSED,
    WAIVED,
}
