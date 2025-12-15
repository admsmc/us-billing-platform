package com.example.uspayroll.worker.http

import com.example.uspayroll.web.WebHeaders
import com.example.uspayroll.worker.jobs.FinalizePayRunJob
import com.example.uspayroll.worker.jobs.FinalizePayRunJobQueue
import com.example.uspayroll.worker.jobs.FinalizePayRunJobStore
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/jobs/employers/{employerId}/payruns")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    prefix = "worker.jobs.legacy-payrun",
    name = ["enabled"],
    havingValue = "true",
)
class WorkerPayRunJobController(
    private val queue: FinalizePayRunJobQueue,
    private val store: FinalizePayRunJobStore,
) {

    data class FinalizeJobRequest(
        val payPeriodId: String,
        val employeeIds: List<String>,
        val requestedPayRunId: String? = null,
        val idempotencyKey: String? = null,
    )

    @PostMapping("/finalize")
    fun finalize(
        @PathVariable employerId: String,
        @RequestHeader(name = WebHeaders.IDEMPOTENCY_KEY, required = false) headerIdempotencyKey: String?,
        @RequestBody request: FinalizeJobRequest,
    ): ResponseEntity<Map<String, Any?>> {
        val bodyKey = request.idempotencyKey?.takeIf { it.isNotBlank() }
        val headerKey = headerIdempotencyKey?.takeIf { it.isNotBlank() }

        val resolvedIdempotencyKey = when {
            headerKey == null -> bodyKey
            bodyKey == null -> headerKey
            headerKey == bodyKey -> headerKey
            else -> {
                return ResponseEntity.badRequest().body(
                    mapOf("error" to "Idempotency-Key header does not match request body idempotencyKey"),
                )
            }
        }

        // Deterministic job id: idempotency key => one job per employer/key.
        // NOTE: this endpoint is legacy/dev-only; production should prefer orchestrator-driven queue semantics.
        val jobId = if (resolvedIdempotencyKey == null) {
            "job-${UUID.randomUUID()}"
        } else {
            val stable = UUID.nameUUIDFromBytes("$employerId:$resolvedIdempotencyKey".toByteArray())
            "job-$stable"
        }

        val existing = store.get(jobId)
        if (existing != null) {
            val body = mapOf(
                "jobId" to existing.jobId,
                "status" to existing.status.name,
                "payRunId" to existing.payRunId,
                "finalStatus" to existing.finalStatus,
                "error" to existing.error,
            )

            return if (resolvedIdempotencyKey == null) {
                ResponseEntity.accepted().body(body)
            } else {
                ResponseEntity.accepted()
                    .header(WebHeaders.IDEMPOTENCY_KEY, resolvedIdempotencyKey)
                    .body(body)
            }
        }

        val job = FinalizePayRunJob(
            jobId = jobId,
            employerId = employerId,
            payPeriodId = request.payPeriodId,
            employeeIds = request.employeeIds,
            requestedPayRunId = request.requestedPayRunId,
            idempotencyKey = resolvedIdempotencyKey,
        )

        store.initQueued(job)
        queue.enqueue(job)

        val body = mapOf(
            "jobId" to jobId,
            "status" to "QUEUED",
        )

        return if (resolvedIdempotencyKey == null) {
            ResponseEntity.accepted().body(body)
        } else {
            ResponseEntity.accepted()
                .header(WebHeaders.IDEMPOTENCY_KEY, resolvedIdempotencyKey)
                .body(body)
        }
    }

    @GetMapping("/finalize/{jobId}")
    fun getFinalizeJobStatus(@PathVariable employerId: String, @PathVariable jobId: String): ResponseEntity<Map<String, Any?>> {
        val result = store.get(jobId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            mapOf(
                "jobId" to result.jobId,
                "status" to result.status.name,
                "payRunId" to result.payRunId,
                "finalStatus" to result.finalStatus,
                "error" to result.error,
            ),
        )
    }
}
