package com.example.usbilling.orchestrator.payrun

import com.example.usbilling.orchestrator.jobs.PayRunFinalizeJobProducer
import com.example.usbilling.orchestrator.payments.PaymentRequestService
import com.example.usbilling.orchestrator.payrun.model.ApprovalStatus
import com.example.usbilling.orchestrator.payrun.model.PayRunRecord
import com.example.usbilling.orchestrator.payrun.model.PayRunStatus
import com.example.usbilling.orchestrator.payrun.model.PayRunStatusCounts
import com.example.usbilling.orchestrator.payrun.model.PayRunType
import com.example.usbilling.orchestrator.payrun.model.PaymentStatus
import com.example.usbilling.orchestrator.payrun.persistence.PayRunItemRepository
import com.example.usbilling.orchestrator.payrun.persistence.PayRunRepository
import com.example.usbilling.orchestrator.persistence.PaycheckLifecycleRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class PayRunService(
    private val payRunRepository: PayRunRepository,
    private val payRunItemRepository: PayRunItemRepository,
    private val paycheckLifecycleRepository: PaycheckLifecycleRepository,
    private val paymentRequestService: PaymentRequestService,
    private val jobProducer: PayRunFinalizeJobProducer,
    private val earningOverridesCodec: PayRunEarningOverridesCodec,
    private val outboxEnqueuer: com.example.uspayroll.orchestrator.events.PayRunOutboxEnqueuer,
) {

    data class StartPayRunResult(
        val payRun: PayRunRecord,
        val counts: PayRunStatusCounts,
        val wasCreated: Boolean,
    )

    @Transactional
    fun startFinalization(
        employerId: String,
        payPeriodId: String,
        employeeIds: List<String>,
        runType: PayRunType = PayRunType.REGULAR,
        runSequence: Int = 1,
        earningOverridesByEmployeeId: Map<String, List<PayRunEarningOverride>> = emptyMap(),
        requestedPayRunId: String? = null,
        idempotencyKey: String? = null,
    ): StartPayRunResult {
        val payRunId = requestedPayRunId ?: "run-${UUID.randomUUID()}"

        val existingByKey = if (idempotencyKey != null) {
            payRunRepository.findByIdempotencyKey(employerId, idempotencyKey)
        } else {
            null
        }

        if (existingByKey != null) {
            val counts = payRunItemRepository.countsForPayRun(employerId, existingByKey.payRunId)
            return StartPayRunResult(payRun = existingByKey, counts = counts, wasCreated = false)
        }

        val createOrGet = payRunRepository.createOrGetPayRun(
            employerId = employerId,
            payRunId = payRunId,
            payPeriodId = payPeriodId,
            runType = runType,
            runSequence = runSequence,
            requestedIdempotencyKey = idempotencyKey,
        )

        if (!createOrGet.wasCreated) {
            val existing = createOrGet.payRun
            val counts = payRunItemRepository.countsForPayRun(employerId, existing.payRunId)
            return StartPayRunResult(payRun = existing, counts = counts, wasCreated = false)
        }

        val payRun = createOrGet.payRun

        payRunItemRepository.upsertQueuedItems(
            employerId = employerId,
            payRunId = payRun.payRunId,
            employeeIds = employeeIds,
        )

        if (earningOverridesByEmployeeId.isNotEmpty()) {
            val jsonByEmployee = earningOverridesByEmployeeId.mapValues { (_, overrides) -> earningOverridesCodec.encode(overrides) }
            payRunItemRepository.setEarningOverridesIfAbsentBatch(
                employerId = employerId,
                payRunId = payRun.payRunId,
                earningOverridesByEmployeeId = jsonByEmployee,
            )
        }

        // Assign paycheck IDs up-front so queue-driven job payloads can carry them.
        // This avoids needing orchestrator-side ID generation at execution time.
        val distinctEmployeeIds = employeeIds.distinct()
        val newPaycheckIds = distinctEmployeeIds.associateWith { "chk-${UUID.randomUUID()}" }
        payRunItemRepository.assignPaycheckIdsIfAbsentBatch(
            employerId = employerId,
            payRunId = payRun.payRunId,
            paycheckIdsByEmployeeId = newPaycheckIds,
        )
        val paycheckIdsByEmployee = payRunItemRepository.findPaycheckIds(
            employerId = employerId,
            payRunId = payRun.payRunId,
            employeeIds = distinctEmployeeIds,
        )

        val overridesByEmployee: Map<String, List<com.example.uspayroll.messaging.jobs.PayRunEarningOverrideJob>> =
            earningOverridesByEmployeeId.mapValues { (_, overrides) ->
                overrides.map { o ->
                    com.example.uspayroll.messaging.jobs.PayRunEarningOverrideJob(
                        code = o.code,
                        units = o.units,
                        rateCents = o.rateCents,
                        amountCents = o.amountCents,
                    )
                }
            }

        // If we are using queue-driven execution, enqueue one work item per employee.
        jobProducer.enqueueFinalizeEmployeeJobs(
            employerId = employerId,
            payRunId = payRun.payRunId,
            payPeriodId = payRun.payPeriodId,
            runType = payRun.runType.name,
            runSequence = payRun.runSequence,
            paycheckIdsByEmployeeId = paycheckIdsByEmployee,
            earningOverridesByEmployeeId = overridesByEmployee,
        )

        // Mark the run as RUNNING so the finalizer can pick it up.
        payRunRepository.markRunningIfQueued(employerId, payRun.payRunId)

        val counts = payRunItemRepository.countsForPayRun(employerId, payRun.payRunId)
        val updatedPayRun = payRunRepository.findPayRun(employerId, payRun.payRunId) ?: payRun
        return StartPayRunResult(payRun = updatedPayRun, counts = counts, wasCreated = true)
    }

    /**
     * Async-first finalization pattern for scalability.
     *
     * Creates pay_run with PENDING status, enqueues bulk item creation job, returns immediately.
     * A dedicated worker will:
     * 1. Insert pay_run_item rows in chunks
     * 2. Publish per-employee finalize jobs
     * 3. Background finalizer transitions PENDING -> RUNNING -> FINALIZED
     */
    @Transactional
    fun startFinalizationAsync(
        employerId: String,
        payPeriodId: String,
        employeeIds: List<String>,
        runType: PayRunType = PayRunType.REGULAR,
        runSequence: Int = 1,
        earningOverridesByEmployeeId: Map<String, List<PayRunEarningOverride>> = emptyMap(),
        requestedPayRunId: String? = null,
        idempotencyKey: String? = null,
        chunkSize: Int = 2000,
    ): StartPayRunResult {
        val payRunId = requestedPayRunId ?: "run-${UUID.randomUUID()}"

        val existingByKey = if (idempotencyKey != null) {
            payRunRepository.findByIdempotencyKey(employerId, idempotencyKey)
        } else {
            null
        }

        if (existingByKey != null) {
            val counts = payRunItemRepository.countsForPayRun(employerId, existingByKey.payRunId)
            return StartPayRunResult(payRun = existingByKey, counts = counts, wasCreated = false)
        }

        val createOrGet = payRunRepository.createOrGetPayRun(
            employerId = employerId,
            payRunId = payRunId,
            payPeriodId = payPeriodId,
            runType = runType,
            runSequence = runSequence,
            requestedIdempotencyKey = idempotencyKey,
            initialStatus = PayRunStatus.PENDING,
        )

        if (!createOrGet.wasCreated) {
            val existing = createOrGet.payRun
            val counts = payRunItemRepository.countsForPayRun(employerId, existing.payRunId)
            return StartPayRunResult(payRun = existing, counts = counts, wasCreated = false)
        }

        val payRun = createOrGet.payRun

        // Convert overrides to messaging format.
        val overridesByEmployee: Map<String, List<com.example.uspayroll.messaging.jobs.PayRunEarningOverrideJob>> =
            earningOverridesByEmployeeId.mapValues { (_, overrides) ->
                overrides.map { o ->
                    com.example.uspayroll.messaging.jobs.PayRunEarningOverrideJob(
                        code = o.code,
                        units = o.units,
                        rateCents = o.rateCents,
                        amountCents = o.amountCents,
                    )
                }
            }

        // Enqueue bulk item creation job.
        jobProducer.enqueueCreateItemsJob(
            employerId = employerId,
            payRunId = payRun.payRunId,
            payPeriodId = payRun.payPeriodId,
            runType = payRun.runType.name,
            runSequence = payRun.runSequence,
            employeeIds = employeeIds,
            earningOverridesByEmployeeId = overridesByEmployee,
            chunkSize = chunkSize,
        )

        // Return immediately with PENDING status and zero counts.
        val counts = PayRunStatusCounts(total = 0, queued = 0, running = 0, succeeded = 0, failed = 0)
        return StartPayRunResult(payRun = payRun, counts = counts, wasCreated = true)
    }

    data class PayRunStatusView(
        val payRun: PayRunRecord,
        val counts: PayRunStatusCounts,
        val failures: List<FailureItem>,
    ) {
        data class FailureItem(val employeeId: String, val error: String?)

        val effectiveStatus: PayRunStatus = when {
            counts.total == 0 -> payRun.status
            counts.queued + counts.running > 0 -> PayRunStatus.RUNNING
            counts.failed == 0 && counts.succeeded == counts.total -> PayRunStatus.FINALIZED
            counts.succeeded > 0 && counts.failed > 0 -> PayRunStatus.PARTIALLY_FINALIZED
            counts.succeeded == 0 && counts.failed == counts.total -> PayRunStatus.FAILED
            else -> payRun.status
        }
    }

    fun getStatus(employerId: String, payRunId: String, failureLimit: Int = 25): PayRunStatusView? {
        val payRun = payRunRepository.findPayRun(employerId, payRunId)
            ?: return null

        val counts = payRunItemRepository.countsForPayRun(employerId, payRunId)
        val failures = payRunItemRepository.listFailedItems(employerId, payRunId, limit = failureLimit)
            .map { (employeeId, err) -> PayRunStatusView.FailureItem(employeeId, err) }

        return PayRunStatusView(
            payRun = payRun.copy(status = payRun.status),
            counts = counts,
            failures = failures,
        )
    }

    fun markFinalizeCompletedIfNull(employerId: String, payRunId: String): Boolean = payRunRepository.markFinalizeCompletedIfNull(employerId, payRunId)

    data class ApproveResult(
        val payRun: PayRunRecord,
        val effectiveStatus: PayRunStatus,
    )

    @Transactional
    fun approvePayRun(employerId: String, payRunId: String): ApproveResult {
        val view = getStatus(employerId, payRunId, failureLimit = 0)
            ?: notFound("payrun not found")

        when (view.effectiveStatus) {
            PayRunStatus.FINALIZED, PayRunStatus.PARTIALLY_FINALIZED -> Unit
            PayRunStatus.FAILED -> conflict("cannot approve failed payrun")
            else -> conflict("payrun not yet finalized")
        }

        val existing = view.payRun
        if (existing.approvalStatus == ApprovalStatus.APPROVED) {
            // Idempotent retry: ensure reporting/filings events are present.
            outboxEnqueuer.enqueuePaycheckLedgerEventsForApprovedPayRun(employerId = employerId, payRunId = payRunId)
            return ApproveResult(payRun = existing, effectiveStatus = view.effectiveStatus)
        }
        if (existing.approvalStatus != ApprovalStatus.PENDING) {
            conflict("payrun approval_status=${existing.approvalStatus}")
        }

        payRunRepository.markApprovedIfPending(employerId, payRunId)
        paycheckLifecycleRepository.setApprovalStatusForPayRun(
            employerId = employerId,
            payRunId = payRunId,
            approvalStatus = ApprovalStatus.APPROVED,
        )

        outboxEnqueuer.enqueuePaycheckLedgerEventsForApprovedPayRun(employerId = employerId, payRunId = payRunId)

        val updated = payRunRepository.findPayRun(employerId, payRunId)
            ?: notFound("payrun not found")

        return ApproveResult(payRun = updated, effectiveStatus = view.effectiveStatus)
    }

    data class InitiatePaymentsResult(
        val payRun: PayRunRecord,
        val effectiveStatus: PayRunStatus,
        val candidates: Int,
        val enqueuedEvents: Int,
    )

    fun initiatePayments(employerId: String, payRunId: String, idempotencyKey: String? = null): InitiatePaymentsResult {
        val view = getStatus(employerId, payRunId, failureLimit = 0)
            ?: notFound("payrun not found")

        when (view.effectiveStatus) {
            PayRunStatus.FINALIZED, PayRunStatus.PARTIALLY_FINALIZED -> Unit
            PayRunStatus.FAILED -> conflict("cannot pay a failed payrun")
            else -> conflict("payrun not yet finalized")
        }

        val payRun = view.payRun
        if (payRun.runType == com.example.uspayroll.orchestrator.payrun.model.PayRunType.VOID) {
            conflict("cannot initiate payments for VOID payrun")
        }
        if (payRun.approvalStatus != ApprovalStatus.APPROVED) {
            conflict("payrun must be approved before payment")
        }

        val normalizedKey = idempotencyKey?.takeIf { it.isNotBlank() }
        if (normalizedKey != null) {
            val existingPayRunId = payRunRepository.findPayRunIdByPaymentInitiateIdempotencyKey(employerId, normalizedKey)
            if (existingPayRunId != null && existingPayRunId != payRunId) {
                conflict("idempotency key already used for another payrun")
            }

            try {
                val accepted = payRunRepository.acceptPaymentInitiateIdempotencyKey(employerId, payRunId, normalizedKey)
                if (!accepted) {
                    conflict("payrun payment initiation idempotency key mismatch")
                }
            } catch (_: DataIntegrityViolationException) {
                // Unique constraint collision (another payrun already used this key).
                conflict("idempotency key already used for another payrun")
            }
        }

        // If already terminal, treat as idempotent no-op. In the target architecture,
        // payments-service is authoritative, and orchestrator is a projection.
        if (payRun.paymentStatus == PaymentStatus.PAID) {
            return InitiatePaymentsResult(
                payRun = payRun,
                effectiveStatus = view.effectiveStatus,
                candidates = 0,
                enqueuedEvents = 0,
            )
        }

        // Mark as in-flight (projection) and enqueue durable payment requests.
        payRunRepository.setPaymentStatus(employerId, payRunId, PaymentStatus.PAYING)

        val enqueue = paymentRequestService.requestPaymentsForPayRun(
            employerId = employerId,
            payRunId = payRunId,
            paymentStatus = PaymentStatus.PAYING,
        )

        val updated = payRunRepository.findPayRun(employerId, payRunId)
            ?: notFound("payrun not found")

        return InitiatePaymentsResult(
            payRun = updated,
            effectiveStatus = view.effectiveStatus,
            candidates = enqueue.candidates,
            enqueuedEvents = enqueue.enqueued,
        )
    }

    private fun notFound(message: String): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND, message)

    private fun conflict(message: String): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, message)
}
