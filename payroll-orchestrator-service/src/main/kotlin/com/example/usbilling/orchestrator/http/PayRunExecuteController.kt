package com.example.usbilling.orchestrator.http

import com.example.usbilling.orchestrator.payrun.PayRunExecutionService
import com.example.usbilling.shared.EmployerId
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Legacy time-sliced execution endpoint ("execute loop").
 *
 * Not part of the queue-driven happy path; intended for benchmarks/dev only.
 */
@RestController
@RequestMapping("/employers/{employerId}/payruns")
@ConditionalOnProperty(prefix = "orchestrator.payrun.execute", name = ["enabled"], havingValue = "true")
class PayRunExecuteController(
    private val executionService: PayRunExecutionService,
) {

    data class ExecutePayRunResponse(
        val employerId: String,
        val payRunId: String,
        val acquiredLease: Boolean,
        val processed: Int,
        val finalStatus: String?,
        val moreWork: Boolean,
    )

    @PostMapping("/internal/{payRunId}/execute")
    fun execute(
        @PathVariable employerId: String,
        @PathVariable payRunId: String,
        @RequestParam(name = "batchSize", defaultValue = "25") batchSize: Int,
        @RequestParam(name = "maxItems", defaultValue = "200") maxItems: Int,
        @RequestParam(name = "maxMillis", defaultValue = "2000") maxMillis: Long,
        @RequestParam(name = "requeueStaleMillis", defaultValue = "600000") requeueStaleMillis: Long,
        @RequestParam(name = "leaseOwner", defaultValue = "worker") leaseOwner: String,
        @RequestParam(name = "parallelism", defaultValue = "4") parallelism: Int,
    ): ResponseEntity<ExecutePayRunResponse> {
        val result = executionService.executePayRun(
            employerId = EmployerId(employerId).value,
            payRunId = payRunId,
            batchSize = batchSize,
            maxItems = maxItems,
            maxMillis = maxMillis,
            requeueStaleMillis = requeueStaleMillis,
            leaseOwner = leaseOwner,
            parallelism = parallelism,
        )

        return ResponseEntity.ok(
            ExecutePayRunResponse(
                employerId = employerId,
                payRunId = payRunId,
                acquiredLease = result.acquiredLease,
                processed = result.processed,
                finalStatus = result.finalStatus?.name,
                moreWork = result.moreWork,
            ),
        )
    }
}
