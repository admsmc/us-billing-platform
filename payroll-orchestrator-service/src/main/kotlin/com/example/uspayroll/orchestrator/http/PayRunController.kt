package com.example.uspayroll.orchestrator.http

import com.example.uspayroll.orchestrator.payrun.PayRunExecutionService
import com.example.uspayroll.orchestrator.payrun.PayRunService
import com.example.uspayroll.orchestrator.payrun.model.PayRunStatus
import com.example.uspayroll.shared.EmployerId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/employers/{employerId}/payruns")
class PayRunController(
    private val payRunService: PayRunService,
    private val executionService: PayRunExecutionService,
) {

    data class StartFinalizeRequest(
        val payPeriodId: String,
        val employeeIds: List<String>,
        /** Defaults to REGULAR. */
        val runType: String? = null,
        /** Defaults to 1. */
        val runSequence: Int? = null,
        val requestedPayRunId: String? = null,
        val idempotencyKey: String? = null,
    )

    data class StartFinalizeResponse(
        val employerId: String,
        val payRunId: String,
        val status: PayRunStatus,
        val totalItems: Int,
        val created: Boolean,
    )

    @PostMapping("/finalize")
    fun startFinalize(
        @PathVariable employerId: String,
        @RequestBody request: StartFinalizeRequest,
    ): ResponseEntity<StartFinalizeResponse> {
        val runType = request.runType?.let { com.example.uspayroll.orchestrator.payrun.model.PayRunType.valueOf(it) }
            ?: com.example.uspayroll.orchestrator.payrun.model.PayRunType.REGULAR
        val runSequence = request.runSequence ?: 1

        val result = payRunService.startFinalization(
            employerId = employerId,
            payPeriodId = request.payPeriodId,
            employeeIds = request.employeeIds,
            runType = runType,
            runSequence = runSequence,
            requestedPayRunId = request.requestedPayRunId,
            idempotencyKey = request.idempotencyKey,
        )

        val statusView = payRunService.getStatus(employerId, result.payRun.payRunId)
        val status = statusView?.effectiveStatus ?: result.payRun.status

        return ResponseEntity.accepted().body(
            StartFinalizeResponse(
                employerId = employerId,
                payRunId = result.payRun.payRunId,
                status = status,
                totalItems = result.counts.total,
                created = result.wasCreated,
            )
        )
    }

    data class PayRunStatusResponse(
        val employerId: String,
        val payRunId: String,
        val payPeriodId: String,
        val status: PayRunStatus,
        val approvalStatus: com.example.uspayroll.orchestrator.payrun.model.ApprovalStatus,
        val paymentStatus: com.example.uspayroll.orchestrator.payrun.model.PaymentStatus,
        val counts: Counts,
        val failures: List<FailureItem>,
    ) {
        data class Counts(
            val total: Int,
            val queued: Int,
            val running: Int,
            val succeeded: Int,
            val failed: Int,
        )

        data class FailureItem(val employeeId: String, val error: String?)
    }

    @GetMapping("/{payRunId}")
    fun getStatus(
        @PathVariable employerId: String,
        @PathVariable payRunId: String,
        @RequestParam(name = "failureLimit", defaultValue = "25") failureLimit: Int,
    ): ResponseEntity<PayRunStatusResponse> {
        val view = payRunService.getStatus(employerId, payRunId, failureLimit = failureLimit)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(
            PayRunStatusResponse(
                employerId = employerId,
                payRunId = view.payRun.payRunId,
                payPeriodId = view.payRun.payPeriodId,
                status = view.effectiveStatus,
                approvalStatus = view.payRun.approvalStatus,
                paymentStatus = view.payRun.paymentStatus,
                counts = PayRunStatusResponse.Counts(
                    total = view.counts.total,
                    queued = view.counts.queued,
                    running = view.counts.running,
                    succeeded = view.counts.succeeded,
                    failed = view.counts.failed,
                ),
                failures = view.failures.map { PayRunStatusResponse.FailureItem(it.employeeId, it.error) },
            )
        )
    }

    data class ApprovePayRunResponse(
        val employerId: String,
        val payRunId: String,
        val status: PayRunStatus,
        val approvalStatus: com.example.uspayroll.orchestrator.payrun.model.ApprovalStatus,
        val paymentStatus: com.example.uspayroll.orchestrator.payrun.model.PaymentStatus,
    )

    @PostMapping("/{payRunId}/approve")
    fun approve(
        @PathVariable employerId: String,
        @PathVariable payRunId: String,
    ): ResponseEntity<ApprovePayRunResponse> {
        val result = payRunService.approvePayRun(employerId, payRunId)
        return ResponseEntity.ok(
            ApprovePayRunResponse(
                employerId = employerId,
                payRunId = payRunId,
                status = result.effectiveStatus,
                approvalStatus = result.payRun.approvalStatus,
                paymentStatus = result.payRun.paymentStatus,
            )
        )
    }

    data class InitiatePaymentsResponse(
        val employerId: String,
        val payRunId: String,
        val status: PayRunStatus,
        val approvalStatus: com.example.uspayroll.orchestrator.payrun.model.ApprovalStatus,
        val paymentStatus: com.example.uspayroll.orchestrator.payrun.model.PaymentStatus,
        val candidates: Int,
        val enqueuedEvents: Int,
    )

    @PostMapping("/{payRunId}/payments/initiate")
    fun initiatePayments(
        @PathVariable employerId: String,
        @PathVariable payRunId: String,
    ): ResponseEntity<InitiatePaymentsResponse> {
        val result = payRunService.initiatePayments(employerId, payRunId)
        return ResponseEntity.ok(
            InitiatePaymentsResponse(
                employerId = employerId,
                payRunId = payRunId,
                status = result.effectiveStatus,
                approvalStatus = result.payRun.approvalStatus,
                paymentStatus = result.payRun.paymentStatus,
                candidates = result.candidates,
                enqueuedEvents = result.enqueuedEvents,
            )
        )
    }

    /**
     * Internal execution trigger used by worker-service.
     *
     * In a production deployment this should be protected by network policy
     * and/or auth; for now it's simply namespaced under /internal.
     */
    @PostMapping("/internal/{payRunId}/execute")
    fun execute(
        @PathVariable employerId: String,
        @PathVariable payRunId: String,
        @RequestParam(name = "batchSize", defaultValue = "25") batchSize: Int,
        @RequestParam(name = "maxItems", defaultValue = "200") maxItems: Int,
        @RequestParam(name = "maxMillis", defaultValue = "2000") maxMillis: Long,
        @RequestParam(name = "requeueStaleMillis", defaultValue = "600000") requeueStaleMillis: Long,
        @RequestParam(name = "leaseOwner", defaultValue = "worker") leaseOwner: String,
    ): ResponseEntity<Map<String, Any?>> {
        val result = executionService.executePayRun(
            employerId = EmployerId(employerId).value,
            payRunId = payRunId,
            batchSize = batchSize,
            maxItems = maxItems,
            maxMillis = maxMillis,
            requeueStaleMillis = requeueStaleMillis,
            leaseOwner = leaseOwner,
        )

        return ResponseEntity.ok(
            mapOf(
                "acquiredLease" to result.acquiredLease,
                "processed" to result.processed,
                "finalStatus" to result.finalStatus?.name,
                "moreWork" to result.moreWork,
            )
        )
    }
}
