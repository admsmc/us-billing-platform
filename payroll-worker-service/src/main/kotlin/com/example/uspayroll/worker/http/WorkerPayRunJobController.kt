package com.example.uspayroll.worker.http

import com.example.uspayroll.worker.jobs.FinalizePayRunJob
import com.example.uspayroll.worker.jobs.FinalizePayRunJobQueue
import com.example.uspayroll.worker.jobs.FinalizePayRunJobStore
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/jobs/employers/{employerId}/payruns")
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
        @RequestBody request: FinalizeJobRequest,
    ): ResponseEntity<Map<String, Any?>> {
        val jobId = "job-${UUID.randomUUID()}"
        val job = FinalizePayRunJob(
            jobId = jobId,
            employerId = employerId,
            payPeriodId = request.payPeriodId,
            employeeIds = request.employeeIds,
            requestedPayRunId = request.requestedPayRunId,
            idempotencyKey = request.idempotencyKey,
        )

        store.initQueued(job)
        queue.enqueue(job)

        return ResponseEntity.accepted().body(
            mapOf(
                "jobId" to jobId,
                "status" to "QUEUED",
            )
        )
    }

    @GetMapping("/finalize/{jobId}")
    fun getFinalizeJobStatus(
        @PathVariable employerId: String,
        @PathVariable jobId: String,
    ): ResponseEntity<Map<String, Any?>> {
        val result = store.get(jobId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            mapOf(
                "jobId" to result.jobId,
                "status" to result.status.name,
                "payRunId" to result.payRunId,
                "finalStatus" to result.finalStatus,
                "error" to result.error,
            )
        )
    }
}
