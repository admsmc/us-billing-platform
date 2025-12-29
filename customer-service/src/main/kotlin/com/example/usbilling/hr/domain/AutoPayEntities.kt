package com.example.usbilling.hr.domain

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Auto-pay enrollment - customer's autopay configuration.
 * Mutable entity (can be updated/cancelled), but no SCD2 needed as
 * autopay_execution provides complete audit trail.
 */
data class AutoPayEnrollment(
    val enrollmentId: String,
    val utilityId: String,
    val customerId: String,
    val accountId: String,
    val paymentMethodId: String,
    val status: AutoPayStatus,
    val paymentTiming: PaymentTiming,
    val fixedDayOfMonth: Int?, // 1-28 if FIXED_DAY
    val amountType: AutoPayAmountType,
    val fixedAmountCents: Long?, // if FIXED_AMOUNT
    val enrolledAt: LocalDateTime,
    val enrolledBy: String,
    val cancelledAt: LocalDateTime?,
    val cancelledReason: String?,
    val consecutiveFailures: Int,
)

/**
 * Auto-pay execution record - append-only audit trail.
 */
data class AutoPayExecution(
    val executionId: String,
    val enrollmentId: String,
    val billId: String?,
    val scheduledDate: LocalDate,
    val executedAt: LocalDateTime?,
    val amountCents: Long,
    val status: ExecutionStatus,
    val failureReason: String?,
    val paymentId: String?,
    val retryCount: Int,
    val createdAt: LocalDateTime,
)

enum class AutoPayStatus {
    ACTIVE,
    SUSPENDED,
    CANCELLED,
}

enum class PaymentTiming {
    ON_DUE_DATE,
    FIXED_DAY,
}

enum class AutoPayAmountType {
    FULL_BALANCE,
    MINIMUM_DUE,
    FIXED_AMOUNT,
}

enum class ExecutionStatus {
    SCHEDULED,
    SUCCESS,
    FAILED,
    SKIPPED,
}
