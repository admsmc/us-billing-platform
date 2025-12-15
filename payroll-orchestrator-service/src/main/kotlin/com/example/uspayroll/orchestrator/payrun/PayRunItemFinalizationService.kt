package com.example.uspayroll.orchestrator.payrun

import com.example.uspayroll.orchestrator.payrun.model.PayRunItemStatus
import com.example.uspayroll.orchestrator.payrun.persistence.PayRunItemRepository
import com.example.uspayroll.orchestrator.payrun.persistence.PayRunRepository
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service

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
    private val paycheckComputationService: PaycheckComputationService,
    private val earningOverridesCodec: PayRunEarningOverridesCodec,
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

    fun finalizeOneEmployeeItem(employerId: String, payRunId: String, employeeId: String): FinalizeItemResult? {
        return finalizeTimer.recordCallable {
            val payRun = payRunRepository.findPayRun(employerId, payRunId) ?: return@recordCallable null

            val claim = payRunItemRepository.claimItem(
                employerId = employerId,
                payRunId = payRunId,
                employeeId = employeeId,
                requeueStaleMillis = props.requeueStaleMillis,
            ) ?: return@recordCallable null

            // If we didn't claim, return current state.
            if (!claim.claimed) {
                val current = claim.item
                val retryable = current.status == PayRunItemStatus.QUEUED || current.status == PayRunItemStatus.RUNNING
                noopCounter.increment()
                return@recordCallable FinalizeItemResult(
                    employerId = employerId,
                    payRunId = payRunId,
                    employeeId = employeeId,
                    itemStatus = current.status.name,
                    attemptCount = current.attemptCount,
                    paycheckId = current.paycheckId,
                    retryable = retryable,
                    error = current.lastError,
                )
            }

            val paycheckId = payRunItemRepository.getOrAssignPaycheckId(
                employerId = employerId,
                payRunId = payRunId,
                employeeId = employeeId,
            )

            @Suppress("TooGenericExceptionCaught")
            try {
                val earningOverrides = claim.item.earningOverridesJson
                    ?.let { earningOverridesCodec.decodeToEarningInputs(it) }
                    ?: emptyList()

                val paycheck = paycheckComputationService.computeAndPersistFinalPaycheckForEmployee(
                    employerId = EmployerId(employerId),
                    payRunId = payRunId,
                    payPeriodId = payRun.payPeriodId,
                    runType = payRun.runType,
                    runSequence = payRun.runSequence,
                    paycheckId = paycheckId,
                    employeeId = EmployeeId(employeeId),
                    earningOverrides = earningOverrides,
                )

                payRunItemRepository.markSucceeded(
                    employerId = employerId,
                    payRunId = payRunId,
                    employeeId = employeeId,
                    paycheckId = paycheck.paycheckId.value,
                )

                val updated = payRunItemRepository.findItem(employerId, payRunId, employeeId)
                succeededCounter.increment()

                return@recordCallable FinalizeItemResult(
                    employerId = employerId,
                    payRunId = payRunId,
                    employeeId = employeeId,
                    itemStatus = PayRunItemStatus.SUCCEEDED.name,
                    attemptCount = updated?.attemptCount ?: claim.item.attemptCount,
                    paycheckId = paycheck.paycheckId.value,
                    retryable = false,
                )
            } catch (t: Exception) {
                val latest = payRunItemRepository.findItem(employerId, payRunId, employeeId)
                val attempts = latest?.attemptCount ?: claim.item.attemptCount
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

                val final = payRunItemRepository.findItem(employerId, payRunId, employeeId)
                return@recordCallable FinalizeItemResult(
                    employerId = employerId,
                    payRunId = payRunId,
                    employeeId = employeeId,
                    itemStatus = final?.status?.name ?: (if (retryable) PayRunItemStatus.QUEUED.name else PayRunItemStatus.FAILED.name),
                    attemptCount = final?.attemptCount ?: attempts,
                    paycheckId = final?.paycheckId ?: paycheckId,
                    retryable = retryable,
                    error = msg,
                )
            }
        }
    }
}
