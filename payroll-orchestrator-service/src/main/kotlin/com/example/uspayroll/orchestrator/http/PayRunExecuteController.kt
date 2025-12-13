package com.example.uspayroll.orchestrator.http

import com.example.uspayroll.orchestrator.payrun.PayRunExecutionService
import com.example.uspayroll.shared.EmployerId
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
    ): ResponseEntity<Map<String, Any?>> {
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
            mapOf(
                "acquiredLease" to result.acquiredLease,
                "processed" to result.processed,
                "finalStatus" to result.finalStatus?.name,
                "moreWork" to result.moreWork,
            ),
        )
    }
}
