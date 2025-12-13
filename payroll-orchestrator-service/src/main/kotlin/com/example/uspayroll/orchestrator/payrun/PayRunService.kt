package com.example.uspayroll.orchestrator.payrun

import com.example.uspayroll.orchestrator.jobs.PayRunFinalizeJobProducer
import com.example.uspayroll.orchestrator.payments.PaymentRequestService
import com.example.uspayroll.orchestrator.payrun.model.ApprovalStatus
import com.example.uspayroll.orchestrator.payrun.model.PayRunRecord
import com.example.uspayroll.orchestrator.payrun.model.PayRunStatus
import com.example.uspayroll.orchestrator.payrun.model.PayRunStatusCounts
import com.example.uspayroll.orchestrator.payrun.model.PayRunType
import com.example.uspayroll.orchestrator.payrun.model.PaymentStatus
import com.example.uspayroll.orchestrator.payrun.persistence.PayRunItemRepository
import com.example.uspayroll.orchestrator.payrun.persistence.PayRunRepository
import com.example.uspayroll.orchestrator.persistence.PaycheckLifecycleRepository
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

        // If we are using queue-driven execution, enqueue one work item per employee.
        jobProducer.enqueueFinalizeEmployeeJobs(
            employerId = employerId,
            payRunId = payRun.payRunId,
            employeeIds = employeeIds,
        )

        // Mark the run as RUNNING so the finalizer can pick it up.
        payRunRepository.markRunningIfQueued(employerId, payRun.payRunId)

        val counts = payRunItemRepository.countsForPayRun(employerId, payRun.payRunId)
        val updatedPayRun = payRunRepository.findPayRun(employerId, payRun.payRunId) ?: payRun
        return StartPayRunResult(payRun = updatedPayRun, counts = counts, wasCreated = true)
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

    data class ApproveResult(
        val payRun: PayRunRecord,
        val effectiveStatus: PayRunStatus,
    )

    fun approvePayRun(employerId: String, payRunId: String): ApproveResult {
        val view = getStatus(employerId, payRunId, failureLimit = 0)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "payrun not found")

        when (view.effectiveStatus) {
            PayRunStatus.FINALIZED, PayRunStatus.PARTIALLY_FINALIZED -> Unit
            PayRunStatus.FAILED -> throw ResponseStatusException(HttpStatus.CONFLICT, "cannot approve failed payrun")
            else -> throw ResponseStatusException(HttpStatus.CONFLICT, "payrun not yet finalized")
        }

        val existing = view.payRun
        if (existing.approvalStatus == ApprovalStatus.APPROVED) {
            return ApproveResult(payRun = existing, effectiveStatus = view.effectiveStatus)
        }
        if (existing.approvalStatus != ApprovalStatus.PENDING) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "payrun approval_status=${existing.approvalStatus}")
        }

        payRunRepository.markApprovedIfPending(employerId, payRunId)
        paycheckLifecycleRepository.setApprovalStatusForPayRun(
            employerId = employerId,
            payRunId = payRunId,
            approvalStatus = ApprovalStatus.APPROVED,
        )

        val updated = payRunRepository.findPayRun(employerId, payRunId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "payrun not found")

        return ApproveResult(payRun = updated, effectiveStatus = view.effectiveStatus)
    }

    data class InitiatePaymentsResult(
        val payRun: PayRunRecord,
        val effectiveStatus: PayRunStatus,
        val candidates: Int,
        val enqueuedEvents: Int,
    )

    fun initiatePayments(employerId: String, payRunId: String): InitiatePaymentsResult {
        val view = getStatus(employerId, payRunId, failureLimit = 0)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "payrun not found")

        when (view.effectiveStatus) {
            PayRunStatus.FINALIZED, PayRunStatus.PARTIALLY_FINALIZED -> Unit
            PayRunStatus.FAILED -> throw ResponseStatusException(HttpStatus.CONFLICT, "cannot pay a failed payrun")
            else -> throw ResponseStatusException(HttpStatus.CONFLICT, "payrun not yet finalized")
        }

        val payRun = view.payRun
        if (payRun.approvalStatus != ApprovalStatus.APPROVED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "payrun must be approved before payment")
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
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "payrun not found")

        return InitiatePaymentsResult(
            payRun = updated,
            effectiveStatus = view.effectiveStatus,
            candidates = enqueue.candidates,
            enqueuedEvents = enqueue.enqueued,
        )
    }
}
