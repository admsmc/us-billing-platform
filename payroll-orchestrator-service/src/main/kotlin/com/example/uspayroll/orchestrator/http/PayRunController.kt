package com.example.uspayroll.orchestrator.http

import com.example.uspayroll.orchestrator.payrun.PayRunService
import com.example.uspayroll.orchestrator.payrun.model.PayRunStatus
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.web.WebHeaders
import com.example.uspayroll.web.security.SecurityAuditLogger
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant

@RestController
@RequestMapping("/employers/{employerId}/payruns")
class PayRunController(
    private val payRunService: PayRunService,
    private val itemFinalizationService: com.example.uspayroll.orchestrator.payrun.PayRunItemFinalizationService,
    private val correctionsService: com.example.uspayroll.orchestrator.payrun.PayRunCorrectionsService,
    private val retroAdjustmentsService: com.example.uspayroll.orchestrator.payrun.PayRunRetroAdjustmentsService,
) {

    data class StartFinalizeRequest(
        val payPeriodId: String,
        val employeeIds: List<String>,
        /** Defaults to REGULAR. */
        val runType: String? = null,
        /** Defaults to 1. */
        val runSequence: Int? = null,
        /**
         * Optional per-employee earning overrides.
         *
         * Intended for off-cycle runs (bonuses/commissions) where base earnings are suppressed.
         */
        val earningOverridesByEmployeeId: Map<String, List<com.example.uspayroll.orchestrator.payrun.PayRunEarningOverride>>? = null,
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
        @RequestHeader(name = WebHeaders.IDEMPOTENCY_KEY, required = false) headerIdempotencyKey: String?,
        @RequestBody request: StartFinalizeRequest,
    ): ResponseEntity<StartFinalizeResponse> {
        val runType = request.runType?.let { com.example.uspayroll.orchestrator.payrun.model.PayRunType.valueOf(it) }
            ?: com.example.uspayroll.orchestrator.payrun.model.PayRunType.REGULAR
        val runSequence = request.runSequence ?: 1

        val bodyKey = request.idempotencyKey?.takeIf { it.isNotBlank() }
        val headerKey = headerIdempotencyKey?.takeIf { it.isNotBlank() }

        val resolvedIdempotencyKey = when {
            headerKey == null -> bodyKey
            bodyKey == null -> headerKey
            headerKey == bodyKey -> headerKey
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header does not match request body idempotencyKey")
        }

        val overrides = request.earningOverridesByEmployeeId ?: emptyMap()
        if (overrides.isNotEmpty()) {
            val allowed = request.employeeIds.toSet()
            val extra = overrides.keys.filterNot { it in allowed }
            if (extra.isNotEmpty()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "earningOverridesByEmployeeId contains employeeIds not in employeeIds list: $extra")
            }
        }

        val result = payRunService.startFinalization(
            employerId = employerId,
            payPeriodId = request.payPeriodId,
            employeeIds = request.employeeIds,
            runType = runType,
            runSequence = runSequence,
            earningOverridesByEmployeeId = overrides,
            requestedPayRunId = request.requestedPayRunId,
            idempotencyKey = resolvedIdempotencyKey,
        )

        val statusView = payRunService.getStatus(employerId, result.payRun.payRunId)
        val status = statusView?.effectiveStatus ?: result.payRun.status

        val response = StartFinalizeResponse(
            employerId = employerId,
            payRunId = result.payRun.payRunId,
            status = status,
            totalItems = result.counts.total,
            created = result.wasCreated,
        )

        return if (resolvedIdempotencyKey.isNullOrBlank()) {
            ResponseEntity.accepted().body(response)
        } else {
            ResponseEntity.accepted()
                .header(WebHeaders.IDEMPOTENCY_KEY, resolvedIdempotencyKey)
                .body(response)
        }
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
        // Server-side timestamps for finalize timing.
        val finalizeStartedAt: Instant?,
        val finalizeCompletedAt: Instant?,
        // Derived server-side e2e latency for finalize (ms).
        val finalizeE2eMs: Long?,
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

        val startedAt = view.payRun.finalizeStartedAt

        // If the run is terminal (computed from item counts) but finalize_completed_at hasn't been set yet,
        // set it now so benchmarks can rely on a stable server-side duration.
        if (startedAt != null && view.effectiveStatus in setOf(PayRunStatus.FINALIZED, PayRunStatus.PARTIALLY_FINALIZED, PayRunStatus.FAILED)) {
            // idempotent best-effort; ignore the return value
            payRunService.markFinalizeCompletedIfNull(employerId, payRunId)
        }

        // Re-read so we return consistent timestamps.
        val refreshed = payRunService.getStatus(employerId, payRunId, failureLimit = failureLimit) ?: view
        val completedAt = refreshed.payRun.finalizeCompletedAt

        val e2eMs = if (startedAt != null && completedAt != null) {
            Duration.between(startedAt, completedAt).toMillis()
        } else {
            null
        }

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
                finalizeStartedAt = startedAt,
                finalizeCompletedAt = completedAt,
                finalizeE2eMs = e2eMs,
            ),
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
    fun approve(@PathVariable employerId: String, @PathVariable payRunId: String): ResponseEntity<ApprovePayRunResponse> {
        val method = "POST"
        val path = "/employers/$employerId/payruns/$payRunId/approve"

        @Suppress("TooGenericExceptionCaught")
        try {
            val result = payRunService.approvePayRun(employerId, payRunId)

            SecurityAuditLogger.privilegedOperationGranted(
                component = "orchestrator",
                method = method,
                path = path,
                operation = "payroll_approve",
                status = HttpStatus.OK.value(),
                employerId = employerId,
            )

            return ResponseEntity.ok(
                ApprovePayRunResponse(
                    employerId = employerId,
                    payRunId = payRunId,
                    status = result.effectiveStatus,
                    approvalStatus = result.payRun.approvalStatus,
                    paymentStatus = result.payRun.paymentStatus,
                ),
            )
        } catch (ex: ResponseStatusException) {
            SecurityAuditLogger.privilegedOperationFailed(
                component = "orchestrator",
                method = method,
                path = path,
                operation = "payroll_approve",
                status = ex.statusCode.value(),
                reason = ex.reason ?: "error",
                employerId = employerId,
            )
            throw ex
        } catch (t: Exception) {
            SecurityAuditLogger.privilegedOperationFailed(
                component = "orchestrator",
                method = method,
                path = path,
                operation = "payroll_approve",
                status = HttpStatus.INTERNAL_SERVER_ERROR.value(),
                reason = t.message ?: t::class.java.name,
                employerId = employerId,
            )
            throw t
        }
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

    data class StartCorrectionRequest(
        val requestedPayRunId: String? = null,
        val runSequence: Int? = null,
        val idempotencyKey: String? = null,
    )

    data class StartCorrectionResponse(
        val employerId: String,
        val sourcePayRunId: String,
        val correctionPayRunId: String,
        val runType: String,
        val runSequence: Int,
        val status: PayRunStatus,
        val totalItems: Int,
        val succeeded: Int,
        val failed: Int,
        val created: Boolean,
    )

    data class StartRetroAdjustmentResponse(
        val employerId: String,
        val sourcePayRunId: String,
        val adjustmentPayRunId: String,
        val runType: String,
        val runSequence: Int,
        val status: PayRunStatus,
        val totalItems: Int,
        val succeeded: Int,
        val failed: Int,
        val created: Boolean,
    )

    @PostMapping("/{payRunId}/payments/initiate")
    fun initiatePayments(
        @PathVariable employerId: String,
        @PathVariable payRunId: String,
        @RequestHeader(name = WebHeaders.IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
    ): ResponseEntity<InitiatePaymentsResponse> {
        val method = "POST"
        val path = "/employers/$employerId/payruns/$payRunId/payments/initiate"

        val normalizedKey = idempotencyKey?.takeIf { it.isNotBlank() }

        @Suppress("TooGenericExceptionCaught")
        try {
            val result = payRunService.initiatePayments(employerId, payRunId, idempotencyKey = normalizedKey)

            SecurityAuditLogger.privilegedOperationGranted(
                component = "orchestrator",
                method = method,
                path = path,
                operation = "payroll_payments_initiate",
                status = HttpStatus.OK.value(),
                employerId = employerId,
            )

            val body = InitiatePaymentsResponse(
                employerId = employerId,
                payRunId = payRunId,
                status = result.effectiveStatus,
                approvalStatus = result.payRun.approvalStatus,
                paymentStatus = result.payRun.paymentStatus,
                candidates = result.candidates,
                enqueuedEvents = result.enqueuedEvents,
            )

            return if (normalizedKey == null) {
                ResponseEntity.ok(body)
            } else {
                ResponseEntity.ok()
                    .header(WebHeaders.IDEMPOTENCY_KEY, normalizedKey)
                    .body(body)
            }
        } catch (ex: ResponseStatusException) {
            SecurityAuditLogger.privilegedOperationFailed(
                component = "orchestrator",
                method = method,
                path = path,
                operation = "payroll_payments_initiate",
                status = ex.statusCode.value(),
                reason = ex.reason ?: "error",
                employerId = employerId,
            )
            throw ex
        } catch (t: Exception) {
            SecurityAuditLogger.privilegedOperationFailed(
                component = "orchestrator",
                method = method,
                path = path,
                operation = "payroll_payments_initiate",
                status = HttpStatus.INTERNAL_SERVER_ERROR.value(),
                reason = t.message ?: t::class.java.name,
                employerId = employerId,
            )
            throw t
        }
    }

    @PostMapping("/{payRunId}/void")
    fun voidPayRun(
        @PathVariable employerId: String,
        @PathVariable payRunId: String,
        @RequestHeader(name = WebHeaders.IDEMPOTENCY_KEY, required = false) headerIdempotencyKey: String?,
        @RequestBody(required = false) request: StartCorrectionRequest?,
    ): ResponseEntity<StartCorrectionResponse> {
        val bodyKey = request?.idempotencyKey?.takeIf { it.isNotBlank() }
        val headerKey = headerIdempotencyKey?.takeIf { it.isNotBlank() }

        val resolvedIdempotencyKey = when {
            headerKey == null -> bodyKey
            bodyKey == null -> headerKey
            headerKey == bodyKey -> headerKey
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header does not match request body idempotencyKey")
        }

        val result = correctionsService.startVoid(
            employerId = employerId,
            sourcePayRunId = payRunId,
            requestedPayRunId = request?.requestedPayRunId,
            runSequenceOverride = request?.runSequence,
            idempotencyKey = resolvedIdempotencyKey,
        )

        val body = StartCorrectionResponse(
            employerId = employerId,
            sourcePayRunId = result.sourcePayRunId,
            correctionPayRunId = result.correctionPayRunId,
            runType = result.runType.name,
            runSequence = result.runSequence,
            status = result.status,
            totalItems = result.totalItems,
            succeeded = result.succeeded,
            failed = result.failed,
            created = result.created,
        )

        return if (resolvedIdempotencyKey.isNullOrBlank()) {
            ResponseEntity.accepted().body(body)
        } else {
            ResponseEntity.accepted()
                .header(WebHeaders.IDEMPOTENCY_KEY, resolvedIdempotencyKey)
                .body(body)
        }
    }

    @PostMapping("/{payRunId}/retro")
    fun startRetroAdjustment(
        @PathVariable employerId: String,
        @PathVariable payRunId: String,
        @RequestHeader(name = WebHeaders.IDEMPOTENCY_KEY, required = false) headerIdempotencyKey: String?,
        @RequestBody(required = false) request: StartCorrectionRequest?,
    ): ResponseEntity<StartRetroAdjustmentResponse> {
        val bodyKey = request?.idempotencyKey?.takeIf { it.isNotBlank() }
        val headerKey = headerIdempotencyKey?.takeIf { it.isNotBlank() }

        val resolvedIdempotencyKey = when {
            headerKey == null -> bodyKey
            bodyKey == null -> headerKey
            headerKey == bodyKey -> headerKey
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header does not match request body idempotencyKey")
        }

        val result = retroAdjustmentsService.startRetroAdjustment(
            employerId = employerId,
            sourcePayRunId = payRunId,
            requestedPayRunId = request?.requestedPayRunId,
            runSequenceOverride = request?.runSequence,
            idempotencyKey = resolvedIdempotencyKey,
        )

        val body = StartRetroAdjustmentResponse(
            employerId = employerId,
            sourcePayRunId = result.sourcePayRunId,
            adjustmentPayRunId = result.adjustmentPayRunId,
            runType = com.example.uspayroll.orchestrator.payrun.model.PayRunType.ADJUSTMENT.name,
            runSequence = result.runSequence,
            status = result.status,
            totalItems = result.totalItems,
            succeeded = result.succeeded,
            failed = result.failed,
            created = result.created,
        )

        return if (resolvedIdempotencyKey.isNullOrBlank()) {
            ResponseEntity.accepted().body(body)
        } else {
            ResponseEntity.accepted()
                .header(WebHeaders.IDEMPOTENCY_KEY, resolvedIdempotencyKey)
                .body(body)
        }
    }

    @PostMapping("/{payRunId}/reissue")
    fun reissuePayRun(
        @PathVariable employerId: String,
        @PathVariable payRunId: String,
        @RequestHeader(name = WebHeaders.IDEMPOTENCY_KEY, required = false) headerIdempotencyKey: String?,
        @RequestBody(required = false) request: StartCorrectionRequest?,
    ): ResponseEntity<StartCorrectionResponse> {
        val bodyKey = request?.idempotencyKey?.takeIf { it.isNotBlank() }
        val headerKey = headerIdempotencyKey?.takeIf { it.isNotBlank() }

        val resolvedIdempotencyKey = when {
            headerKey == null -> bodyKey
            bodyKey == null -> headerKey
            headerKey == bodyKey -> headerKey
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header does not match request body idempotencyKey")
        }

        val result = correctionsService.startReissue(
            employerId = employerId,
            sourcePayRunId = payRunId,
            requestedPayRunId = request?.requestedPayRunId,
            runSequenceOverride = request?.runSequence,
            idempotencyKey = resolvedIdempotencyKey,
        )

        val body = StartCorrectionResponse(
            employerId = employerId,
            sourcePayRunId = result.sourcePayRunId,
            correctionPayRunId = result.correctionPayRunId,
            runType = result.runType.name,
            runSequence = result.runSequence,
            status = result.status,
            totalItems = result.totalItems,
            succeeded = result.succeeded,
            failed = result.failed,
            created = result.created,
        )

        return if (resolvedIdempotencyKey.isNullOrBlank()) {
            ResponseEntity.accepted().body(body)
        } else {
            ResponseEntity.accepted()
                .header(WebHeaders.IDEMPOTENCY_KEY, resolvedIdempotencyKey)
                .body(body)
        }
    }

    /**
     * Internal endpoint: finalize a single employee item.
     *
     * Intended for queue-driven execution (RabbitMQ) where many worker replicas
     * call into orchestrator to perform the DB-backed finalize step idempotently.
     */
    @PostMapping("/internal/{payRunId}/items/{employeeId}/finalize")
    fun finalizeEmployeeItem(@PathVariable employerId: String, @PathVariable payRunId: String, @PathVariable employeeId: String): ResponseEntity<Map<String, Any?>> {
        val result = itemFinalizationService.finalizeOneEmployeeItem(
            employerId = EmployerId(employerId).value,
            payRunId = payRunId,
            employeeId = employeeId,
        ) ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(
            mapOf(
                "employerId" to result.employerId,
                "payRunId" to result.payRunId,
                "employeeId" to result.employeeId,
                "itemStatus" to result.itemStatus,
                "attemptCount" to result.attemptCount,
                "paycheckId" to result.paycheckId,
                "retryable" to result.retryable,
                "error" to result.error,
            ),
        )
    }
}
