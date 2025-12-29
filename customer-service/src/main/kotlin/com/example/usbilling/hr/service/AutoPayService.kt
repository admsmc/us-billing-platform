package com.example.usbilling.hr.service

import com.example.usbilling.hr.domain.*
import com.example.usbilling.hr.repository.AutoPayEnrollmentRepository
import com.example.usbilling.hr.repository.AutoPayExecutionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*

@Service
class AutoPayService(
    private val enrollmentRepository: AutoPayEnrollmentRepository,
    private val executionRepository: AutoPayExecutionRepository,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val MAX_RETRY_ATTEMPTS = 3
    }

    /**
     * Enroll customer in auto-pay.
     */
    fun enroll(
        utilityId: String,
        customerId: String,
        accountId: String,
        paymentMethodId: String,
        paymentTiming: PaymentTiming,
        fixedDayOfMonth: Int?,
        amountType: AutoPayAmountType,
        fixedAmountCents: Long?,
        enrolledBy: String,
    ): AutoPayEnrollment {
        // Validation
        require(paymentTiming != PaymentTiming.FIXED_DAY || fixedDayOfMonth != null) {
            "Fixed day of month required for FIXED_DAY timing"
        }
        require(fixedDayOfMonth == null || fixedDayOfMonth in 1..28) {
            "Fixed day must be between 1 and 28"
        }
        require(amountType != AutoPayAmountType.FIXED_AMOUNT || (fixedAmountCents != null && fixedAmountCents > 0)) {
            "Fixed amount required for FIXED_AMOUNT type"
        }

        // Check for existing enrollment
        val existing = enrollmentRepository.findByAccountId(accountId)
        if (existing != null && existing.status == AutoPayStatus.ACTIVE) {
            throw IllegalStateException("Account already has active auto-pay enrollment")
        }

        val now = LocalDateTime.now()
        val enrollment = AutoPayEnrollment(
            enrollmentId = UUID.randomUUID().toString(),
            utilityId = utilityId,
            customerId = customerId,
            accountId = accountId,
            paymentMethodId = paymentMethodId,
            status = AutoPayStatus.ACTIVE,
            paymentTiming = paymentTiming,
            fixedDayOfMonth = fixedDayOfMonth,
            amountType = amountType,
            fixedAmountCents = fixedAmountCents,
            enrolledAt = now,
            enrolledBy = enrolledBy,
            cancelledAt = null,
            cancelledReason = null,
            consecutiveFailures = 0,
        )

        return enrollmentRepository.save(enrollment)
    }

    /**
     * Update auto-pay enrollment settings.
     */
    fun updateEnrollment(
        enrollmentId: String,
        paymentTiming: PaymentTiming?,
        fixedDayOfMonth: Int?,
        amountType: AutoPayAmountType?,
        fixedAmountCents: Long?,
    ): AutoPayEnrollment {
        val existing = enrollmentRepository.findById(enrollmentId)
            ?: throw IllegalArgumentException("Enrollment not found: $enrollmentId")

        if (existing.status != AutoPayStatus.ACTIVE) {
            throw IllegalStateException("Cannot update non-active enrollment")
        }

        val updated = existing.copy(
            paymentTiming = paymentTiming ?: existing.paymentTiming,
            fixedDayOfMonth = fixedDayOfMonth ?: existing.fixedDayOfMonth,
            amountType = amountType ?: existing.amountType,
            fixedAmountCents = fixedAmountCents ?: existing.fixedAmountCents,
        )

        return enrollmentRepository.save(updated)
    }

    /**
     * Cancel auto-pay enrollment.
     */
    fun cancelEnrollment(enrollmentId: String, reason: String): AutoPayEnrollment {
        val existing = enrollmentRepository.findById(enrollmentId)
            ?: throw IllegalArgumentException("Enrollment not found: $enrollmentId")

        if (existing.status == AutoPayStatus.CANCELLED) {
            throw IllegalStateException("Enrollment already cancelled")
        }

        val now = LocalDateTime.now()
        val cancelled = existing.copy(
            status = AutoPayStatus.CANCELLED,
            cancelledAt = now,
            cancelledReason = reason,
        )

        return enrollmentRepository.save(cancelled)
    }

    /**
     * Process auto-pay executions for a given date.
     * Called by scheduled job.
     */
    fun processAutoPayForDate(date: LocalDate) {
        logger.info("Processing auto-pay for date: $date")
        val activeEnrollments = enrollmentRepository.findActiveEnrollmentsDueOn(date)

        for (enrollment in activeEnrollments) {
            if (shouldProcessEnrollment(enrollment, date)) {
                try {
                    processEnrollment(enrollment, date)
                } catch (e: Exception) {
                    logger.error("Failed to process enrollment ${enrollment.enrollmentId}", e)
                }
            }
        }

        // Retry failed executions
        retryFailedExecutions()
    }

    /**
     * Determine if enrollment should be processed on given date.
     */
    private fun shouldProcessEnrollment(enrollment: AutoPayEnrollment, date: LocalDate): Boolean {
        return when (enrollment.paymentTiming) {
            PaymentTiming.ON_DUE_DATE -> {
                // Would need to query for bills with due date = date
                // For now, simplified logic
                true
            }
            PaymentTiming.FIXED_DAY -> {
                date.dayOfMonth == enrollment.fixedDayOfMonth
            }
        }
    }

    /**
     * Process a single enrollment.
     */
    private fun processEnrollment(enrollment: AutoPayEnrollment, scheduledDate: LocalDate) {
        // Determine payment amount
        val amount = calculatePaymentAmount(enrollment)

        // Create execution record
        val now = LocalDateTime.now()
        val execution = AutoPayExecution(
            executionId = UUID.randomUUID().toString(),
            enrollmentId = enrollment.enrollmentId,
            billId = null, // Would be determined from bill query
            scheduledDate = scheduledDate,
            executedAt = null,
            amountCents = amount,
            status = ExecutionStatus.SCHEDULED,
            failureReason = null,
            paymentId = null,
            retryCount = 0,
            createdAt = now,
        )

        executionRepository.save(execution)

        // Execute payment
        executePayment(execution, enrollment)
    }

    /**
     * Calculate payment amount based on enrollment configuration.
     */
    private fun calculatePaymentAmount(enrollment: AutoPayEnrollment): Long {
        return when (enrollment.amountType) {
            AutoPayAmountType.FULL_BALANCE -> {
                // Would query billing service for current balance
                // For now, placeholder
                10000L // $100
            }
            AutoPayAmountType.MINIMUM_DUE -> {
                // Would query billing service for minimum due
                5000L // $50
            }
            AutoPayAmountType.FIXED_AMOUNT -> {
                enrollment.fixedAmountCents ?: throw IllegalStateException("Fixed amount not set")
            }
        }
    }

    /**
     * Execute payment via payments service.
     */
    private fun executePayment(execution: AutoPayExecution, enrollment: AutoPayEnrollment) {
        try {
            // Call payments service to process payment
            // For now, simulated
            val paymentId = UUID.randomUUID().toString()

            // Update execution as success
            executionRepository.updateStatus(
                executionId = execution.executionId,
                status = ExecutionStatus.SUCCESS,
                executedAt = LocalDateTime.now(),
                paymentId = paymentId,
                failureReason = null,
                retryCount = execution.retryCount,
            )

            // Reset consecutive failures
            if (enrollment.consecutiveFailures > 0) {
                val updated = enrollment.copy(consecutiveFailures = 0)
                enrollmentRepository.save(updated)
            }

            logger.info("Auto-pay successful: enrollment=${enrollment.enrollmentId}, payment=$paymentId")

            // TODO: Publish AUTOPAY_SUCCESS event
        } catch (e: Exception) {
            handlePaymentFailure(execution, enrollment, e.message ?: "Unknown error")
        }
    }

    /**
     * Handle payment failure.
     */
    private fun handlePaymentFailure(
        execution: AutoPayExecution,
        enrollment: AutoPayEnrollment,
        failureReason: String,
    ) {
        executionRepository.updateStatus(
            executionId = execution.executionId,
            status = ExecutionStatus.FAILED,
            executedAt = LocalDateTime.now(),
            paymentId = null,
            failureReason = failureReason,
            retryCount = execution.retryCount,
        )

        // Increment consecutive failures
        val newFailures = enrollment.consecutiveFailures + 1
        val updated = if (newFailures >= MAX_CONSECUTIVE_FAILURES) {
            // Suspend enrollment
            logger.warn("Suspending enrollment ${enrollment.enrollmentId} after $newFailures consecutive failures")
            enrollment.copy(
                status = AutoPayStatus.SUSPENDED,
                consecutiveFailures = newFailures,
            )
        } else {
            enrollment.copy(consecutiveFailures = newFailures)
        }

        enrollmentRepository.save(updated)

        logger.error("Auto-pay failed: enrollment=${enrollment.enrollmentId}, reason=$failureReason")

        // TODO: Publish AUTOPAY_FAILED event
        if (updated.status == AutoPayStatus.SUSPENDED) {
            // TODO: Publish AUTOPAY_SUSPENDED event
        }
    }

    /**
     * Retry failed executions.
     */
    private fun retryFailedExecutions() {
        val failedExecutions = executionRepository.findFailedForRetry(MAX_RETRY_ATTEMPTS)

        for (execution in failedExecutions) {
            val enrollment = enrollmentRepository.findById(execution.enrollmentId)
            if (enrollment != null && enrollment.status == AutoPayStatus.ACTIVE) {
                try {
                    val retryExecution = execution.copy(
                        executionId = UUID.randomUUID().toString(),
                        retryCount = execution.retryCount + 1,
                        status = ExecutionStatus.SCHEDULED,
                        createdAt = LocalDateTime.now(),
                    )
                    executionRepository.save(retryExecution)
                    executePayment(retryExecution, enrollment)
                } catch (e: Exception) {
                    logger.error("Retry failed for execution ${execution.executionId}", e)
                }
            }
        }
    }

    /**
     * Get enrollment by ID.
     */
    fun getEnrollment(enrollmentId: String): AutoPayEnrollment? =
        enrollmentRepository.findById(enrollmentId)

    /**
     * Get enrollment by account ID.
     */
    fun getEnrollmentByAccountId(accountId: String): AutoPayEnrollment? =
        enrollmentRepository.findByAccountId(accountId)

    /**
     * Get execution history for enrollment.
     */
    fun getExecutionHistory(enrollmentId: String, limit: Int = 50): List<AutoPayExecution> =
        executionRepository.findByEnrollmentId(enrollmentId, limit)
}
