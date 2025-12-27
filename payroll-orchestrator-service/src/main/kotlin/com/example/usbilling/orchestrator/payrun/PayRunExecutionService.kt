package com.example.usbilling.orchestrator.payrun

import com.example.usbilling.orchestrator.events.PayRunOutboxEnqueuer
import com.example.usbilling.orchestrator.payrun.model.PayRunStatus
import com.example.usbilling.orchestrator.payrun.persistence.PayRunItemRepository
import com.example.usbilling.orchestrator.payrun.persistence.PayRunRepository
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors

@Service
class PayRunExecutionService(
    private val payRunRepository: PayRunRepository,
    private val payRunItemRepository: PayRunItemRepository,
    private val paycheckComputationService: PaycheckComputationService,
    private val earningOverridesCodec: PayRunEarningOverridesCodec,
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
        /** Max number of employees to process concurrently within a single execute call. */
        parallelism: Int = 4,
    ): ExecuteResult {
        val payRun = payRunRepository.findPayRun(employerId, payRunId)
            ?: return ExecuteResult(acquiredLease = false, processed = 0, finalStatus = null, moreWork = false)

        if (payRun.status == PayRunStatus.FINALIZED ||
            payRun.status == PayRunStatus.PARTIALLY_FINALIZED ||
            payRun.status == PayRunStatus.FAILED
        ) {
            return ExecuteResult(acquiredLease = false, processed = 0, finalStatus = payRun.status, moreWork = false)
        }

        val acquired = payRunRepository.acquireOrRenewLease(
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

        val effectiveParallelism = parallelism.coerceAtLeast(1)
        val executor = if (effectiveParallelism == 1) null else Executors.newFixedThreadPool(effectiveParallelism)

        try {
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
                if (executor == null) {
                    claimedEmployeeIds.forEach { eid ->
                        processOneEmployeeItem(
                            employerId = employerId,
                            payRun = payRun,
                            payRunId = payRunId,
                            employeeId = eid,
                        )
                        processed += 1
                    }
                } else {
                    val futures = claimedEmployeeIds.map { eid ->
                        executor.submit {
                            processOneEmployeeItem(
                                employerId = employerId,
                                payRun = payRun,
                                payRunId = payRunId,
                                employeeId = eid,
                            )
                        }
                    }
                    futures.forEach { it.get() }
                    processed += claimedEmployeeIds.size
                }
            }
        } finally {
            executor?.shutdown()
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

    private fun processOneEmployeeItem(employerId: String, payRun: com.example.usbilling.orchestrator.payrun.model.PayRunRecord, payRunId: String, employeeId: String) {
        try {
            val paycheckId = payRunItemRepository.getOrAssignBillId(
                employerId = employerId,
                payRunId = payRunId,
                employeeId = employeeId,
            )

            val earningOverrides = payRunItemRepository.findItem(employerId, payRunId, employeeId)
                ?.earningOverridesJson
                ?.let { earningOverridesCodec.decodeToEarningInputs(it) }
                ?: emptyList()

            val paycheck = paycheckComputationService.computeAndPersistFinalPaycheckForEmployee(
                employerId = UtilityId(employerId),
                payRunId = payRunId,
                payPeriodId = payRun.payPeriodId,
                runType = payRun.runType,
                runSequence = payRun.runSequence,
                paycheckId = paycheckId,
                employeeId = CustomerId(employeeId),
                earningOverrides = earningOverrides,
            )

            payRunItemRepository.markSucceeded(
                employerId = employerId,
                payRunId = payRunId,
                employeeId = employeeId,
                paycheckId = paycheck.paycheckId.value,
            )
        } catch (t: Throwable) {
            payRunItemRepository.markFailed(
                employerId = employerId,
                payRunId = payRunId,
                employeeId = employeeId,
                error = (t.message ?: t::class.java.name),
            )
        }
    }
}
