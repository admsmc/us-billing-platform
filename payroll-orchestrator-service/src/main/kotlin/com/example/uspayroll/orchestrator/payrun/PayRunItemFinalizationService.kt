package com.example.uspayroll.orchestrator.payrun

import com.example.uspayroll.orchestrator.payrun.model.PayRunItemStatus
import com.example.uspayroll.orchestrator.payrun.persistence.PayRunItemRepository
import com.example.uspayroll.orchestrator.payrun.persistence.PayRunRepository
import com.example.uspayroll.orchestrator.persistence.PaycheckAuditStoreRepository
import com.example.uspayroll.orchestrator.persistence.PaycheckStoreRepository
import com.example.uspayroll.payroll.model.PaycheckResult
import com.example.uspayroll.payroll.model.audit.PaycheckAudit
import com.example.uspayroll.shared.EmployerId
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@ConfigurationProperties(prefix = "orchestrator.payrun.items")
data class PayRunItemFinalizationProperties(
    /** Maximum number of attempts before marking an item terminal FAILED. */
    var maxAttempts: Int = 8,
    /** How long to consider RUNNING items stale and eligible to be requeued. */
    var requeueStaleMillis: Long = 10 * 60 * 1000L,
)

@Service
@EnableConfigurationProperties(PayRunItemFinalizationProperties::class)
class PayRunItemFinalizationService(
    private val props: PayRunItemFinalizationProperties,
    private val payRunRepository: PayRunRepository,
    private val payRunItemRepository: PayRunItemRepository,
    private val paycheckStoreRepository: PaycheckStoreRepository,
    private val paycheckAuditStoreRepository: PaycheckAuditStoreRepository,
    meterRegistry: MeterRegistry,
) {

    private val finalizeTimer: Timer = meterRegistry.timer("orchestrator.payrun.item.finalize.duration")
    private val succeededCounter = meterRegistry.counter("orchestrator.payrun.item.finalize.total", "outcome", "succeeded")
    private val retryableCounter = meterRegistry.counter("orchestrator.payrun.item.finalize.total", "outcome", "retryable")
    private val failedCounter = meterRegistry.counter("orchestrator.payrun.item.finalize.total", "outcome", "failed")
    private val noopCounter = meterRegistry.counter("orchestrator.payrun.item.finalize.total", "outcome", "noop")

    data class FinalizeItemResult(
        val employerId: String,
        val payRunId: String,
        val employeeId: String,
        val itemStatus: String,
        val attemptCount: Int,
        val paycheckId: String?,
        val retryable: Boolean,
        val error: String? = null,
    )

    data class CompleteItemRequest(
        val paycheckId: String,
        val paycheck: PaycheckResult? = null,
        val audit: PaycheckAudit? = null,
        val error: String? = null,
    )

    @Transactional
    fun completeOneEmployeeItem(employerId: String, payRunId: String, employeeId: String, request: CompleteItemRequest): FinalizeItemResult? {
        return finalizeTimer.recordCallable {
            val payRun = payRunRepository.findPayRun(employerId, payRunId) ?: return@recordCallable null

            val claim = payRunItemRepository.claimItem(
                employerId = employerId,
                payRunId = payRunId,
                employeeId = employeeId,
                requeueStaleMillis = props.requeueStaleMillis,
            ) ?: return@recordCallable null

            // If we didn't claim, return current state. We treat non-claimed states as non-retryable
            // to avoid job storms on duplicate delivery.
            if (!claim.claimed) {
                val current = claim.item
                noopCounter.increment()
                return@recordCallable FinalizeItemResult(
                    employerId = employerId,
                    payRunId = payRunId,
                    employeeId = employeeId,
                    itemStatus = current.status.name,
                    attemptCount = current.attemptCount,
                    paycheckId = current.paycheckId,
                    retryable = false,
                    error = current.lastError,
                )
            }

            val resolvedPaycheckId = request.paycheckId

            @Suppress("TooGenericExceptionCaught")
            try {
                if (request.error != null) {
                    throw IllegalStateException(request.error)
                }

                val paycheck = requireNotNull(request.paycheck) { "paycheck is required on success" }
                val audit = requireNotNull(request.audit) { "audit is required on success" }

                paycheckStoreRepository.insertFinalPaycheckIfAbsent(
                    employerId = EmployerId(employerId),
                    paycheckId = resolvedPaycheckId,
                    payRunId = payRunId,
                    employeeId = employeeId,
                    payPeriodId = payRun.payPeriodId,
                    runType = payRun.runType.name,
                    runSequence = payRun.runSequence,
                    checkDateIso = paycheck.period.checkDate.toString(),
                    grossCents = paycheck.gross.amount,
                    netCents = paycheck.net.amount,
                    version = 1,
                    payload = paycheck,
                )

                paycheckAuditStoreRepository.insertAuditIfAbsent(audit)

                payRunItemRepository.markSucceeded(
                    employerId = employerId,
                    payRunId = payRunId,
                    employeeId = employeeId,
                    paycheckId = resolvedPaycheckId,
                )

                succeededCounter.increment()

                return@recordCallable FinalizeItemResult(
                    employerId = employerId,
                    payRunId = payRunId,
                    employeeId = employeeId,
                    itemStatus = PayRunItemStatus.SUCCEEDED.name,
                    attemptCount = claim.item.attemptCount,
                    paycheckId = resolvedPaycheckId,
                    retryable = false,
                )
            } catch (t: Exception) {
                val attempts = claim.item.attemptCount
                val maxAttempts = props.maxAttempts.coerceAtLeast(1)
                val retryable = attempts < maxAttempts

                val msg = t.message ?: t::class.java.name
                if (retryable) {
                    retryableCounter.increment()
                    payRunItemRepository.markRetryableFailure(
                        employerId = employerId,
                        payRunId = payRunId,
                        employeeId = employeeId,
                        error = msg,
                    )
                } else {
                    failedCounter.increment()
                    payRunItemRepository.markFailed(
                        employerId = employerId,
                        payRunId = payRunId,
                        employeeId = employeeId,
                        error = msg,
                    )
                }

                return@recordCallable FinalizeItemResult(
                    employerId = employerId,
                    payRunId = payRunId,
                    employeeId = employeeId,
                    itemStatus = if (retryable) PayRunItemStatus.QUEUED.name else PayRunItemStatus.FAILED.name,
                    attemptCount = attempts,
                    paycheckId = resolvedPaycheckId,
                    retryable = retryable,
                    error = msg,
                )
            }
        }
    }
}
