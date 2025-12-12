package com.example.uspayroll.orchestrator.payrun

import com.example.uspayroll.orchestrator.events.PayRunOutboxEnqueuer
import com.example.uspayroll.orchestrator.payrun.model.PayRunStatus
import com.example.uspayroll.orchestrator.payrun.persistence.PayRunItemRepository
import com.example.uspayroll.orchestrator.payrun.persistence.PayRunRepository
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class PayRunExecutionService(
    private val payRunRepository: PayRunRepository,
    private val payRunItemRepository: PayRunItemRepository,
    private val paycheckComputationService: PaycheckComputationService,
    private val outboxEnqueuer: PayRunOutboxEnqueuer,
) {

    data class ExecuteResult(
        /** True if we successfully acquired (and still hold) the lease during this call. */
        val acquiredLease: Boolean,
        /** Number of employee items processed (success or failure) during this call. */
        val processed: Int,
        /** Final status if determined (terminal or RUNNING); null if payrun not found. */
        val finalStatus: PayRunStatus?,
        /** True when there is still queued/running work remaining after this call. */
        val moreWork: Boolean,
    )

    /**
     * Executes queued items for a payrun. Safe to call multiple times:
     * - Leasing prevents concurrent execution for the same payrun.
     * - Items are claimed in small batches.
     */
    fun executePayRun(
        employerId: String,
        payRunId: String,
        batchSize: Int = 25,
        maxItems: Int = 200,
        maxMillis: Long = 2_000L,
        requeueStaleMillis: Long = 10 * 60 * 1000L,
        leaseOwner: String = "worker",
        leaseDuration: Duration = Duration.ofMinutes(5),
    ): ExecuteResult {
        val payRun = payRunRepository.findPayRun(employerId, payRunId)
            ?: return ExecuteResult(acquiredLease = false, processed = 0, finalStatus = null, moreWork = false)

        if (payRun.status == PayRunStatus.FINALIZED ||
            payRun.status == PayRunStatus.PARTIALLY_FINALIZED ||
            payRun.status == PayRunStatus.FAILED
        ) {
            return ExecuteResult(acquiredLease = false, processed = 0, finalStatus = payRun.status, moreWork = false)
        }

        val acquired = payRunRepository.tryAcquireLease(
            employerId = employerId,
            payRunId = payRunId,
            leaseOwner = leaseOwner,
            leaseDuration = leaseDuration,
        )

        if (!acquired) {
            return ExecuteResult(acquiredLease = false, processed = 0, finalStatus = null, moreWork = true)
        }

        // On lease acquisition, requeue stale RUNNING items from a prior crash.
        val now = Instant.now()
        val cutoff = now.minusMillis(requeueStaleMillis.coerceAtLeast(0L))
        payRunItemRepository.requeueStaleRunningItems(
            employerId = employerId,
            payRunId = payRunId,
            cutoff = cutoff,
            reason = "requeued_stale_running cutoffMs=$requeueStaleMillis",
        )

        val startMs = System.currentTimeMillis()
        val deadlineMs = startMs + maxMillis.coerceAtLeast(1L)
        val maxToProcess = maxItems.coerceAtLeast(0)

        var processed = 0
        var stillOwnLease = true

        while (processed < maxToProcess && System.currentTimeMillis() < deadlineMs) {
            // Heartbeat before each claim/batch.
            stillOwnLease = payRunRepository.renewLeaseIfOwned(
                employerId = employerId,
                payRunId = payRunId,
                leaseOwner = leaseOwner,
                leaseDuration = leaseDuration,
            )
            if (!stillOwnLease) break

            val claimedEmployeeIds = payRunItemRepository.claimQueuedItems(
                employerId = employerId,
                payRunId = payRunId,
                batchSize = minOf(batchSize, maxToProcess - processed),
            )

            if (claimedEmployeeIds.isEmpty()) break

            // Once items are claimed (status=RUNNING), we must process them all
            // to avoid leaving rows stuck in RUNNING.
            claimedEmployeeIds.forEach { eid ->
                try {
                    val paycheckId = payRunItemRepository.getOrAssignPaycheckId(
                        employerId = employerId,
                        payRunId = payRunId,
                        employeeId = eid,
                    )

                    val paycheck = paycheckComputationService.computeAndPersistFinalPaycheckForEmployee(
                        employerId = EmployerId(employerId),
                        payRunId = payRunId,
                        payPeriodId = payRun.payPeriodId,
                        runType = payRun.runType.name,
                        runSequence = payRun.runSequence,
                        paycheckId = paycheckId,
                        employeeId = EmployeeId(eid),
                    )

                    payRunItemRepository.markSucceeded(
                        employerId = employerId,
                        payRunId = payRunId,
                        employeeId = eid,
                        paycheckId = paycheck.paycheckId.value,
                    )
                } catch (t: Throwable) {
                    payRunItemRepository.markFailed(
                        employerId = employerId,
                        payRunId = payRunId,
                        employeeId = eid,
                        error = (t.message ?: t::class.java.name),
                    )
                } finally {
                    processed += 1
                }
            }
        }

        val counts = payRunItemRepository.countsForPayRun(employerId, payRunId)

        val computedStatus = when {
            counts.total == 0 -> PayRunStatus.FAILED
            counts.failed == 0 && counts.succeeded == counts.total -> PayRunStatus.FINALIZED
            counts.succeeded > 0 && counts.failed > 0 -> PayRunStatus.PARTIALLY_FINALIZED
            counts.succeeded == 0 && counts.failed == counts.total -> PayRunStatus.FAILED
            counts.queued + counts.running > 0 -> PayRunStatus.RUNNING
            else -> PayRunStatus.RUNNING
        }

        val moreWork = counts.queued + counts.running > 0

        if (computedStatus != PayRunStatus.RUNNING) {
            // Transactionally set terminal status and enqueue outbox events.
            outboxEnqueuer.finalizePayRunAndEnqueueOutboxEvents(
                employerId = employerId,
                payRunId = payRunId,
                payPeriodId = payRun.payPeriodId,
                status = computedStatus,
                total = counts.total,
                succeeded = counts.succeeded,
                failed = counts.failed,
            )
        }

        // If we lost the lease mid-run, signal caller to retry.
        val acquiredAndHeld = acquired && stillOwnLease

        return ExecuteResult(
            acquiredLease = acquiredAndHeld,
            processed = processed,
            finalStatus = computedStatus,
            moreWork = moreWork,
        )
    }
}
