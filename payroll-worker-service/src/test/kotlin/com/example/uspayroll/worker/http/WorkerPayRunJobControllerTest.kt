package com.example.uspayroll.worker.http

import com.example.uspayroll.worker.jobs.inmemory.InMemoryFinalizePayRunJobQueue
import com.example.uspayroll.worker.jobs.inmemory.InMemoryFinalizePayRunJobStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkerPayRunJobControllerTest {

    @Test
    fun `finalize enqueues job and status endpoint returns it`() {
        val queue = InMemoryFinalizePayRunJobQueue()
        val store = InMemoryFinalizePayRunJobStore()
        val controller = WorkerPayRunJobController(queue, store)

        val resp = controller.finalize(
            employerId = "EMP",
            headerIdempotencyKey = null,
            request = WorkerPayRunJobController.FinalizeJobRequest(
                payPeriodId = "PP",
                employeeIds = listOf("EE1", "EE2"),
                requestedPayRunId = "PR-REQ",
            ),
        )

        assertEquals(202, resp.statusCode.value())
        val jobId = resp.body?.jobId as String

        val statusResp = controller.getFinalizeJobStatus(employerId = "EMP", jobId = jobId)
        assertEquals(200, statusResp.statusCode.value())
        assertEquals("QUEUED", statusResp.body?.status)
    }

    @Test
    fun `finalize is idempotent via Idempotency-Key header`() {
        val queue = InMemoryFinalizePayRunJobQueue()
        val store = InMemoryFinalizePayRunJobStore()
        val controller = WorkerPayRunJobController(queue, store)

        val r1 = controller.finalize(
            employerId = "EMP",
            headerIdempotencyKey = "idem-1",
            request = WorkerPayRunJobController.FinalizeJobRequest(
                payPeriodId = "PP",
                employeeIds = listOf("EE1"),
                requestedPayRunId = "PR-1",
            ),
        )

        val r2 = controller.finalize(
            employerId = "EMP",
            headerIdempotencyKey = "idem-1",
            request = WorkerPayRunJobController.FinalizeJobRequest(
                payPeriodId = "PP",
                employeeIds = listOf("EE1"),
                requestedPayRunId = "PR-2",
            ),
        )

        val jobId1 = r1.body?.jobId as String
        val jobId2 = r2.body?.jobId as String
        assertEquals(jobId1, jobId2)

        // Only one job enqueued.
        val j1 = queue.tryDequeue()
        val j2 = queue.tryDequeue()
        assertEquals(jobId1, j1?.jobId)
        assertEquals(null, j2)
    }
}
