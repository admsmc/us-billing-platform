package com.example.usbilling.orchestrator.http

import com.example.usbilling.messaging.events.payments.PaycheckPaymentLifecycleStatus
import com.example.usbilling.messaging.jobs.PayRunEarningOverrideJob
import com.example.usbilling.orchestrator.client.PaymentsQueryClient
import com.example.usbilling.orchestrator.events.PayRunOutboxEnqueuer
import com.example.usbilling.orchestrator.jobs.PayRunFinalizeJobProducer
import com.example.usbilling.orchestrator.payments.PaymentRequestService
import com.example.usbilling.orchestrator.payments.model.PaycheckPaymentStatus
import com.example.usbilling.orchestrator.payments.persistence.PayRunPaycheckQueryRepository
import com.example.usbilling.orchestrator.payments.persistence.PaycheckPaymentRepository
import com.example.usbilling.orchestrator.payments.persistence.PaymentStatusProjectionRepository
import com.example.usbilling.orchestrator.payrun.PayRunEarningOverride
import com.example.usbilling.orchestrator.payrun.PayRunEarningOverridesCodec
import com.example.usbilling.orchestrator.payrun.PayRunService
import com.example.usbilling.orchestrator.payrun.model.ApprovalStatus
import com.example.usbilling.orchestrator.payrun.model.PayRunItemStatus
import com.example.usbilling.orchestrator.payrun.model.PayRunStatus
import com.example.usbilling.orchestrator.payrun.model.PaymentStatus
import com.example.usbilling.orchestrator.payrun.persistence.PayRunItemRepository
import com.example.usbilling.orchestrator.payrun.persistence.PayRunRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Internal-only reconciliation endpoints.
 *
 * These routes are guarded by InternalAuthFilter because they contain "/payruns/internal/".
 */
@RestController
@RequestMapping("/employers/{employerId}/payruns/internal")
class PayRunReconciliationController(
    private val payRunRepository: PayRunRepository,
    private val payRunItemRepository: PayRunItemRepository,
    private val payRunService: PayRunService,
    private val jobProducer: PayRunFinalizeJobProducer,
    private val paymentRequestService: PaymentRequestService,
    private val payRunPaycheckQueryRepository: PayRunPaycheckQueryRepository,
    private val paymentsQueryClient: PaymentsQueryClient,
    private val outboxEnqueuer: PayRunOutboxEnqueuer,
    private val paymentStatusProjectionRepository: PaymentStatusProjectionRepository,
    private val paycheckPaymentRepository: PaycheckPaymentRepository,
    private val earningOverridesCodec: PayRunEarningOverridesCodec,
) {

    data class StuckPayRunView(
        val employerId: String,
        val payRunId: String,
        val payPeriodId: String,
        val status: String,
        val leaseOwner: String?,
        val leaseExpiresAt: Instant?,
        val finalizeStartedAt: Instant?,
        val finalizeCompletedAt: Instant?,
        val ageMillis: Long,
        val leaseExpired: Boolean,
    )

    @GetMapping("/reconcile/stuck-running")
    fun listStuckRunning(
        @PathVariable employerId: String,
        @RequestParam(name = "olderThanMillis", defaultValue = "600000") olderThanMillis: Long,
        @RequestParam(name = "nowEpochMillis", required = false) nowEpochMillis: Long?,
    ): ResponseEntity<List<StuckPayRunView>> {
        val now = nowEpochMillis?.let { Instant.ofEpochMilli(it) } ?: Instant.now()
        val olderThan = olderThanMillis.coerceAtLeast(0L)

        val running = payRunRepository.listPayRunsByStatus(employerId, PayRunStatus.RUNNING, limit = 500)

        val stuck = running.mapNotNull { pr ->
            val startedAt = pr.finalizeStartedAt
            val age = if (startedAt == null) null else Duration.between(startedAt, now).toMillis()
            val leaseExpired = pr.leaseExpiresAt?.isBefore(now) ?: true

            if (age == null || age < olderThan) return@mapNotNull null

            StuckPayRunView(
                employerId = pr.employerId,
                payRunId = pr.payRunId,
                payPeriodId = pr.payPeriodId,
                status = pr.status.name,
                leaseOwner = pr.leaseOwner,
                leaseExpiresAt = pr.leaseExpiresAt,
                finalizeStartedAt = pr.finalizeStartedAt,
                finalizeCompletedAt = pr.finalizeCompletedAt,
                ageMillis = age,
                leaseExpired = leaseExpired,
            )
        }

        return ResponseEntity.ok(stuck)
    }

    data class RequeueResult(
        val employerId: String,
        val payRunId: String,
        val touchedItems: Int,
        val queuedItems: Int,
        val enqueuedJobs: Int,
    )

    /**
     * Ensures all currently QUEUED items have a corresponding Rabbit outbox job enqueued.
     * Safe to run repeatedly because jobs are idempotent via deterministic outbox eventId.
     */
    @PostMapping("/{payRunId}/reconcile/requeue-queued")
    fun requeueQueued(@PathVariable employerId: String, @PathVariable payRunId: String, @RequestParam(name = "limit", defaultValue = "10000") limit: Int): ResponseEntity<RequeueResult> {
        val payRun = payRunRepository.findPayRun(employerId, payRunId)
            ?: notFound("payrun not found")

        val employeeIds = payRunItemRepository.listEmployeeIdsByStatus(
            employerId = employerId,
            payRunId = payRunId,
            status = PayRunItemStatus.QUEUED,
            limit = limit,
        )

        val paycheckIdsByEmployeeId = ensurePaycheckIds(employerId, payRunId, employeeIds)
        val earningOverridesByEmployeeId = loadEarningOverridesAsJobs(employerId, payRunId, employeeIds)

        val enqueued = jobProducer.enqueueFinalizeEmployeeJobs(
            employerId = employerId,
            payRunId = payRunId,
            payPeriodId = payRun.payPeriodId,
            runType = payRun.runType.name,
            runSequence = payRun.runSequence,
            paycheckIdsByEmployeeId = paycheckIdsByEmployeeId,
            earningOverridesByEmployeeId = earningOverridesByEmployeeId,
        )

        return ResponseEntity.ok(
            RequeueResult(
                employerId = employerId,
                payRunId = payRunId,
                touchedItems = 0,
                queuedItems = employeeIds.size,
                enqueuedJobs = enqueued,
            ),
        )
    }

    /**
     * Requeues stale RUNNING items (based on updated_at), then ensures QUEUED items are enqueued.
     */
    @PostMapping("/{payRunId}/reconcile/requeue-stale-running")
    fun requeueStaleRunning(
        @PathVariable employerId: String,
        @PathVariable payRunId: String,
        @RequestParam(name = "staleMillis", defaultValue = "600000") staleMillis: Long,
        @RequestParam(name = "limit", defaultValue = "10000") limit: Int,
    ): ResponseEntity<RequeueResult> {
        val now = Instant.now()
        val cutoff = now.minusMillis(staleMillis.coerceAtLeast(0L))

        val touched = payRunItemRepository.requeueStaleRunningItems(
            employerId = employerId,
            payRunId = payRunId,
            cutoff = cutoff,
            reason = "reconcile_requeue_stale_running staleMillis=$staleMillis",
        )

        val payRun = payRunRepository.findPayRun(employerId, payRunId)
            ?: notFound("payrun not found")

        val queued = payRunItemRepository.listEmployeeIdsByStatus(
            employerId = employerId,
            payRunId = payRunId,
            status = PayRunItemStatus.QUEUED,
            limit = limit,
        )

        val paycheckIdsByEmployeeId = ensurePaycheckIds(employerId, payRunId, queued)
        val earningOverridesByEmployeeId = loadEarningOverridesAsJobs(employerId, payRunId, queued)

        val enqueued = jobProducer.enqueueFinalizeEmployeeJobs(
            employerId = employerId,
            payRunId = payRunId,
            payPeriodId = payRun.payPeriodId,
            runType = payRun.runType.name,
            runSequence = payRun.runSequence,
            paycheckIdsByEmployeeId = paycheckIdsByEmployeeId,
            earningOverridesByEmployeeId = earningOverridesByEmployeeId,
        )

        return ResponseEntity.ok(
            RequeueResult(
                employerId = employerId,
                payRunId = payRunId,
                touchedItems = touched,
                queuedItems = queued.size,
                enqueuedJobs = enqueued,
            ),
        )
    }

    /**
     * Operator-triggered retry of FAILED items.
     */
    @PostMapping("/{payRunId}/reconcile/requeue-failed")
    fun requeueFailed(
        @PathVariable employerId: String,
        @PathVariable payRunId: String,
        @RequestParam(name = "limit", defaultValue = "1000") limit: Int,
        @RequestParam(name = "reason", defaultValue = "operator_requeue_failed") reason: String,
    ): ResponseEntity<RequeueResult> {
        val failedIds = payRunItemRepository.listEmployeeIdsByStatus(
            employerId = employerId,
            payRunId = payRunId,
            status = PayRunItemStatus.FAILED,
            limit = limit,
        )

        val touched = payRunItemRepository.requeueFailedItems(
            employerId = employerId,
            payRunId = payRunId,
            employeeIds = failedIds,
            reason = reason,
        )

        val payRun = payRunRepository.findPayRun(employerId, payRunId)
            ?: notFound("payrun not found")

        val queued = payRunItemRepository.listEmployeeIdsByStatus(
            employerId = employerId,
            payRunId = payRunId,
            status = PayRunItemStatus.QUEUED,
            limit = limit,
        )

        val paycheckIdsByEmployeeId = ensurePaycheckIds(employerId, payRunId, queued)
        val earningOverridesByEmployeeId = loadEarningOverridesAsJobs(employerId, payRunId, queued)

        val enqueued = jobProducer.enqueueFinalizeEmployeeJobs(
            employerId = employerId,
            payRunId = payRunId,
            payPeriodId = payRun.payPeriodId,
            runType = payRun.runType.name,
            runSequence = payRun.runSequence,
            paycheckIdsByEmployeeId = paycheckIdsByEmployeeId,
            earningOverridesByEmployeeId = earningOverridesByEmployeeId,
        )

        return ResponseEntity.ok(
            RequeueResult(
                employerId = employerId,
                payRunId = payRunId,
                touchedItems = touched,
                queuedItems = queued.size,
                enqueuedJobs = enqueued,
            ),
        )
    }

    data class PaymentReconcileResult(
        val employerId: String,
        val payRunId: String,
        val candidates: Int,
        val enqueuedEvents: Int,
        val paymentStatus: String,
    )

    /**
     * Re-enqueue payment request events for the payrun's succeeded paychecks.
     *
     * Safe to run repeatedly due to deterministic eventId: paycheck-payment-requested:<employer>:<paycheckId>.
     */
    @PostMapping("/{payRunId}/reconcile/payments/re-enqueue-requests")
    fun reenqueuePaymentRequests(@PathVariable employerId: String, @PathVariable payRunId: String): ResponseEntity<PaymentReconcileResult> {
        val view = payRunService.getStatus(employerId, payRunId, failureLimit = 0)
            ?: notFound("payrun not found")

        when (view.effectiveStatus) {
            PayRunStatus.FINALIZED, PayRunStatus.PARTIALLY_FINALIZED -> Unit
            else -> conflict("payrun not finalized")
        }

        if (view.payRun.approvalStatus != ApprovalStatus.APPROVED) {
            conflict("payrun must be approved")
        }

        // Ensure we are at least PAYING; this is monotonic-ish.
        payRunRepository.setPaymentStatus(employerId, payRunId, PaymentStatus.PAYING)

        val enqueue = paymentRequestService.requestPaymentsForPayRun(
            employerId = employerId,
            payRunId = payRunId,
            paymentStatus = PaymentStatus.PAYING,
        )

        val updated = payRunRepository.findPayRun(employerId, payRunId)
            ?: notFound("payrun not found")

        return ResponseEntity.ok(
            PaymentReconcileResult(
                employerId = employerId,
                payRunId = payRunId,
                candidates = enqueue.candidates,
                enqueuedEvents = enqueue.enqueued,
                paymentStatus = updated.paymentStatus.name,
            ),
        )
    }

    private fun ensurePaycheckIds(employerId: String, payRunId: String, employeeIds: List<String>): Map<String, String> {
        if (employeeIds.isEmpty()) return emptyMap()

        var paycheckIds = payRunItemRepository.findPaycheckIds(
            employerId = employerId,
            payRunId = payRunId,
            employeeIds = employeeIds,
        )

        val missing = employeeIds.filterNot { paycheckIds.containsKey(it) }
        if (missing.isNotEmpty()) {
            val newIds = missing.associateWith { "chk-${UUID.randomUUID()}" }
            payRunItemRepository.assignPaycheckIdsIfAbsentBatch(
                employerId = employerId,
                payRunId = payRunId,
                paycheckIdsByEmployeeId = newIds,
            )

            paycheckIds = payRunItemRepository.findPaycheckIds(
                employerId = employerId,
                payRunId = payRunId,
                employeeIds = employeeIds,
            )

            val stillMissing = employeeIds.filterNot { paycheckIds.containsKey(it) }
            if (stillMissing.isNotEmpty()) {
                paycheckIds = paycheckIds + stillMissing.associateWith { employeeId ->
                    payRunItemRepository.getOrAssignPaycheckId(employerId = employerId, payRunId = payRunId, employeeId = employeeId)
                }
            }
        }

        return paycheckIds
    }

    private fun loadEarningOverridesAsJobs(employerId: String, payRunId: String, employeeIds: List<String>): Map<String, List<PayRunEarningOverrideJob>> {
        if (employeeIds.isEmpty()) return emptyMap()

        val jsonByEmployee = payRunItemRepository.findEarningOverridesJson(
            employerId = employerId,
            payRunId = payRunId,
            employeeIds = employeeIds,
        )

        return jsonByEmployee.mapValues { (_, json) ->
            val overrides: List<PayRunEarningOverride> = earningOverridesCodec.decodeToOverrides(json)
            overrides.map { o ->
                PayRunEarningOverrideJob(
                    code = o.code,
                    units = o.units,
                    rateCents = o.rateCents,
                    amountCents = o.amountCents,
                )
            }
        }
    }

    private fun notFound(message: String): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND, message)

    data class FinalizeReconcileResult(
        val employerId: String,
        val payRunId: String,
        val computedStatus: String,
        val total: Int,
        val succeeded: Int,
        val failed: Int,
    )

    private fun computeStatusFromCounts(counts: com.example.usbilling.orchestrator.payrun.model.PayRunStatusCounts): PayRunStatus = when {
        counts.total == 0 -> PayRunStatus.FAILED
        counts.failed == 0 && counts.succeeded == counts.total -> PayRunStatus.FINALIZED
        counts.succeeded > 0 && counts.failed > 0 -> PayRunStatus.PARTIALLY_FINALIZED
        counts.succeeded == 0 && counts.failed == counts.total -> PayRunStatus.FAILED
        counts.queued + counts.running > 0 -> PayRunStatus.RUNNING
        else -> PayRunStatus.RUNNING
    }

    /**
     * Operator trigger: recompute pay_run.status from pay_run_item counts without emitting events.
     *
     * This is useful for repairing drift when operators explicitly do not want to publish.
     */
    @PostMapping("/{payRunId}/reconcile/recompute-run-status")
    fun recomputeRunStatus(
        @PathVariable employerId: String,
        @PathVariable payRunId: String,
        @RequestParam(name = "persist", defaultValue = "true") persist: Boolean,
    ): ResponseEntity<FinalizeReconcileResult> {
        val run = payRunRepository.findPayRun(employerId, payRunId)
            ?: notFound("payrun not found")

        val counts = payRunItemRepository.countsForPayRun(employerId, payRunId)
        val computedStatus = computeStatusFromCounts(counts)

        if (persist && computedStatus != PayRunStatus.RUNNING) {
            // Persist terminal status and release lease, but DO NOT publish outbox events.
            payRunRepository.setFinalStatusAndReleaseLease(
                employerId = employerId,
                payRunId = payRunId,
                status = computedStatus,
            )
        }

        // Return the computed status regardless of whether we persisted it.
        return ResponseEntity.ok(
            FinalizeReconcileResult(
                employerId = employerId,
                payRunId = payRunId,
                computedStatus = computedStatus.name,
                total = counts.total,
                succeeded = counts.succeeded,
                failed = counts.failed,
            ),
        )
    }

    /**
     * Operator trigger: if items are terminal, finalize the payrun and enqueue Kafka outbox events.
     *
     * Safe to call repeatedly because eventIds are deterministic and outbox inserts are idempotent.
     */
    @PostMapping("/{payRunId}/reconcile/finalize-and-enqueue-events")
    fun finalizeAndEnqueueEvents(@PathVariable employerId: String, @PathVariable payRunId: String): ResponseEntity<FinalizeReconcileResult> {
        val run = payRunRepository.findPayRun(employerId, payRunId)
            ?: notFound("payrun not found")

        val counts = payRunItemRepository.countsForPayRun(employerId, payRunId)
        val computedStatus = computeStatusFromCounts(counts)

        if (computedStatus == PayRunStatus.RUNNING) {
            conflict("payrun not terminal")
        }

        outboxEnqueuer.finalizePayRunAndEnqueueOutboxEvents(
            employerId = employerId,
            payRunId = payRunId,
            payPeriodId = run.payPeriodId,
            status = computedStatus,
            total = counts.total,
            succeeded = counts.succeeded,
            failed = counts.failed,
        )

        return ResponseEntity.ok(
            FinalizeReconcileResult(
                employerId = employerId,
                payRunId = payRunId,
                computedStatus = computedStatus.name,
                total = counts.total,
                succeeded = counts.succeeded,
                failed = counts.failed,
            ),
        )
    }

    data class PaymentStatusRecomputeResult(
        val employerId: String,
        val payRunId: String,
        val total: Int,
        val paid: Int,
        val failed: Int,
        val computedPaymentStatus: String,
    )

    /**
     * Operator trigger: recompute pay_run.payment_status from paycheck.payment_status counts.
     */
    @PostMapping("/{payRunId}/reconcile/payments/recompute-run-status")
    fun recomputePaymentStatusForRun(@PathVariable employerId: String, @PathVariable payRunId: String): ResponseEntity<PaymentStatusRecomputeResult> {
        // Ensure payrun exists.
        payRunRepository.findPayRun(employerId, payRunId) ?: notFound("payrun not found")

        val counts = paymentStatusProjectionRepository.getPayRunPaymentCounts(employerId, payRunId)
        if (counts.total <= 0) {
            conflict("no paychecks found for payrun")
        }

        val computed = when {
            counts.paid == counts.total -> PaymentStatus.PAID
            counts.failed == counts.total -> PaymentStatus.FAILED
            counts.paid > 0 && counts.failed > 0 -> PaymentStatus.PARTIALLY_PAID
            else -> PaymentStatus.PAYING
        }

        payRunRepository.setPaymentStatus(
            employerId = employerId,
            payRunId = payRunId,
            paymentStatus = computed,
        )

        return ResponseEntity.ok(
            PaymentStatusRecomputeResult(
                employerId = employerId,
                payRunId = payRunId,
                total = counts.total,
                paid = counts.paid,
                failed = counts.failed,
                computedPaymentStatus = computed.name,
            ),
        )
    }

    data class PaymentProjectionRebuildResult(
        val employerId: String,
        val payRunId: String,
        val totalPaychecks: Int,
        val paymentsFound: Int,
        val missingPayments: Int,
        val updatedPaychecks: Int,
        val computedPaymentStatus: String,
    )

    /**
     * Operator trigger: rebuild orchestrator's payment status projections from payments-service.
     *
     * Safety constraints (defaults):
     * - payrun must be finalized and approved
     * - all succeeded paychecks must have a corresponding payment record in payments-service
     *
     * This endpoint updates only orchestrator projection tables; payments-service remains system-of-record.
     */
    @PostMapping("/{payRunId}/reconcile/payments/rebuild-projection")
    fun rebuildPaymentProjectionFromPaymentsService(
        @PathVariable employerId: String,
        @PathVariable payRunId: String,
        @RequestParam(name = "persist", defaultValue = "true") persist: Boolean,
        @RequestParam(name = "allowMissingPayments", defaultValue = "false") allowMissingPayments: Boolean,
    ): ResponseEntity<PaymentProjectionRebuildResult> {
        val view = payRunService.getStatus(employerId, payRunId, failureLimit = 0)
            ?: notFound("payrun not found")

        when (view.effectiveStatus) {
            PayRunStatus.FINALIZED, PayRunStatus.PARTIALLY_FINALIZED -> Unit
            PayRunStatus.FAILED -> conflict("cannot rebuild payment projection for failed payrun")
            else -> conflict("payrun not finalized")
        }

        if (view.payRun.approvalStatus != ApprovalStatus.APPROVED) {
            conflict("payrun must be approved")
        }

        val candidates = payRunPaycheckQueryRepository.listSucceededPaychecksWithNet(employerId, payRunId)
        if (candidates.isEmpty()) {
            conflict("no succeeded paychecks found for payrun")
        }

        val payments = paymentsQueryClient.listPaymentsForPayRun(employerId, payRunId)
        val byPaycheckId = payments.associateBy { it.paycheckId }

        val missing = candidates.count { !byPaycheckId.containsKey(it.paycheckId) }
        if (missing > 0 && !allowMissingPayments) {
            conflict("missing payments for $missing paychecks")
        }

        var updated = 0
        var paid = 0
        var failed = 0

        candidates.forEach { c ->
            val payment = byPaycheckId[c.paycheckId]
            val mapped = when (payment?.status) {
                PaycheckPaymentLifecycleStatus.SETTLED -> PaymentStatus.PAID
                PaycheckPaymentLifecycleStatus.FAILED -> PaymentStatus.FAILED
                PaycheckPaymentLifecycleStatus.CREATED,
                PaycheckPaymentLifecycleStatus.SUBMITTED,
                null,
                -> PaymentStatus.PAYING
            }

            when (mapped) {
                PaymentStatus.PAID -> paid += 1
                PaymentStatus.FAILED -> failed += 1
                else -> Unit
            }

            if (persist) {
                updated += paymentStatusProjectionRepository.updatePaycheckPaymentStatus(
                    employerId = employerId,
                    paycheckId = c.paycheckId,
                    paymentStatus = mapped,
                )

                // Also refresh paycheck_payment projection for UI/reconciliation.
                val status = payment?.status?.let { PaycheckPaymentStatus.valueOf(it.name) }
                    ?: PaycheckPaymentStatus.CREATED

                // Prefer paymentId from payments-service when available.
                val paymentId = payment?.paymentId ?: "pmt-${c.paycheckId}"

                paycheckPaymentRepository.insertIfAbsent(
                    employerId = employerId,
                    paymentId = paymentId,
                    paycheckId = c.paycheckId,
                    payRunId = payRunId,
                    employeeId = c.employeeId,
                    payPeriodId = c.payPeriodId,
                    currency = c.currency,
                    netCents = c.netCents,
                    status = status,
                )
                paycheckPaymentRepository.updateStatusByPaycheck(employerId, c.paycheckId, status)
            }
        }

        val computedRunStatus = when {
            paid == candidates.size -> PaymentStatus.PAID
            failed == candidates.size -> PaymentStatus.FAILED
            paid > 0 && failed > 0 -> PaymentStatus.PARTIALLY_PAID
            else -> PaymentStatus.PAYING
        }

        if (persist) {
            payRunRepository.setPaymentStatus(
                employerId = employerId,
                payRunId = payRunId,
                paymentStatus = computedRunStatus,
            )
        }

        return ResponseEntity.ok(
            PaymentProjectionRebuildResult(
                employerId = employerId,
                payRunId = payRunId,
                totalPaychecks = candidates.size,
                paymentsFound = payments.size,
                missingPayments = missing,
                updatedPaychecks = updated,
                computedPaymentStatus = computedRunStatus.name,
            ),
        )
    }

    private fun conflict(message: String): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, message)
}
