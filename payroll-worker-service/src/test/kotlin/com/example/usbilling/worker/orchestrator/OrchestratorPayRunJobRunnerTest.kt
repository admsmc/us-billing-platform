package com.example.usbilling.worker.orchestrator

import com.example.usbilling.shared.UtilityId
import com.example.usbilling.worker.client.OrchestratorClient
import com.example.usbilling.worker.client.PayRunStatusResponse
import com.example.usbilling.worker.client.StartFinalizeResponse
import com.example.usbilling.worker.support.WorkerInstance
import com.example.usbilling.worker.support.WorkerInstanceProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class OrchestratorPayRunJobRunnerTest {

    @Test
    fun `runner stops once execute returns terminal finalStatus`() {
        val client = Mockito.mock(OrchestratorClient::class.java)

        Mockito.`when`(
            client.startFinalize(
                UtilityId("EMP"),
                "PP",
                listOf("EE1", "EE2"),
                null,
                "idem-1",
            ),
        ).thenReturn(
            StartFinalizeResponse(
                employerId = "EMP",
                payRunId = "PR-1",
                status = "QUEUED",
                totalItems = 2,
                created = true,
            ),
        )

        Mockito.`when`(
            client.execute(
                UtilityId("EMP"),
                "PR-1",
                25,
                200,
                2_000L,
                10 * 60 * 1000L,
                "worker-test-worker",
                4,
            ),
        )
            .thenReturn(mapOf("finalStatus" to null, "moreWork" to true))
            .thenReturn(mapOf("finalStatus" to "FINALIZED", "moreWork" to false))

        Mockito.`when`(client.getStatus(UtilityId("EMP"), "PR-1", 50)).thenReturn(
            PayRunStatusResponse(
                employerId = "EMP",
                payRunId = "PR-1",
                payPeriodId = "PP",
                status = "FINALIZED",
                approvalStatus = null,
                paymentStatus = null,
                counts = PayRunStatusResponse.Counts(total = 2, queued = 0, running = 0, succeeded = 2, failed = 0),
                failures = emptyList(),
            ),
        )

        val runner = OrchestratorPayRunJobRunner(
            orchestratorClient = client,
            props = OrchestratorPayRunJobProperties(
                executeLoopSleepMillis = 0L,
                maxExecuteIterations = 5,
            ),
            workerInstance = WorkerInstance(WorkerInstanceProperties(id = "test-worker")),
        )

        val result = runner.runFinalizeJob(
            employerId = UtilityId("EMP"),
            payPeriodId = "PP",
            employeeIds = listOf("EE1", "EE2"),
            requestedPayRunId = null,
            idempotencyKey = "idem-1",
        )

        assertEquals("PR-1", result.payRunId)
        assertEquals("FINALIZED", result.finalStatus)

        Mockito.verify(client, Mockito.atLeastOnce()).execute(
            UtilityId("EMP"),
            "PR-1",
            25,
            200,
            2_000L,
            10 * 60 * 1000L,
            "worker-test-worker",
            4,
        )
    }
}
