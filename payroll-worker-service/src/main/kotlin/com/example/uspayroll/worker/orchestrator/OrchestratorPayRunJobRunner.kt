package com.example.uspayroll.worker.orchestrator

import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.worker.client.OrchestratorClient
import com.example.uspayroll.worker.client.PayRunStatusResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service

@Service
@EnableConfigurationProperties(OrchestratorPayRunJobProperties::class)
class OrchestratorPayRunJobRunner(
    private val orchestratorClient: OrchestratorClient,
    private val props: OrchestratorPayRunJobProperties,
) {

    private val logger = LoggerFactory.getLogger(OrchestratorPayRunJobRunner::class.java)

    data class JobResult(
        val payRunId: String,
        val finalStatus: String,
        val lastPoll: PayRunStatusResponse,
    )

    fun runFinalizeJob(
        employerId: EmployerId,
        payPeriodId: String,
        employeeIds: List<String>,
        requestedPayRunId: String? = null,
        idempotencyKey: String? = null,
    ): JobResult {
        val start = orchestratorClient.startFinalize(
            employerId = employerId,
            payPeriodId = payPeriodId,
            employeeIds = employeeIds,
            requestedPayRunId = requestedPayRunId,
            idempotencyKey = idempotencyKey,
        )

        val payRunId = start.payRunId

        // Execute in short time-sliced calls until orchestrator reports a terminal status.
        var iter = 0
        var lastFinalStatus: String? = null

        while (iter < props.maxExecuteIterations) {
            val exec = orchestratorClient.execute(
                employerId = employerId,
                payRunId = payRunId,
                batchSize = props.executeBatchSize,
                maxItems = props.executeMaxItems,
                maxMillis = props.executeMaxMillis,
                requeueStaleMillis = props.requeueStaleMillis,
                leaseOwner = "worker",
            )

            lastFinalStatus = exec["finalStatus"] as? String
            val moreWork = exec["moreWork"] as? Boolean ?: true

            if (lastFinalStatus != null && isTerminal(lastFinalStatus)) {
                break
            }

            // If no more work is reported but status isn't terminal (should be rare), stop and inspect via status.
            if (!moreWork) {
                break
            }

            iter += 1
            Thread.sleep(props.executeLoopSleepMillis)
        }

        val status = orchestratorClient.getStatus(employerId = employerId, payRunId = payRunId, failureLimit = 50)
        val final = status.status

        logger.info(
            "payrun.finalize.completed employer={} pay_run_id={} status={} total={} succeeded={} failed={} iterations={}",
            employerId.value,
            payRunId,
            final,
            status.counts.total,
            status.counts.succeeded,
            status.counts.failed,
            iter,
        )

        return JobResult(payRunId = payRunId, finalStatus = final, lastPoll = status)
    }

    private fun isTerminal(status: String): Boolean = when (status) {
        "FINALIZED", "PARTIALLY_FINALIZED", "FAILED" -> true
        else -> false
    }
}
