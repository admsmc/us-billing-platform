package com.example.usbilling.worker.jobs.inmemory

import com.example.usbilling.worker.jobs.FinalizePayRunJob
import com.example.usbilling.worker.jobs.JobStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InMemoryJobQueueTest {

    @Test
    fun `queue enqueues and dequeues`() {
        val q = InMemoryFinalizePayRunJobQueue()
        val job = FinalizePayRunJob(jobId = "job-1", employerId = "EMP", payPeriodId = "PP", employeeIds = listOf("EE"))

        q.enqueue(job)
        assertEquals(job, q.tryDequeue())
        assertEquals(null, q.tryDequeue())
    }

    @Test
    fun `store lifecycle transitions`() {
        val store = InMemoryFinalizePayRunJobStore()
        val job = FinalizePayRunJob(jobId = "job-1", employerId = "EMP", payPeriodId = "PP", employeeIds = listOf("EE"))

        store.initQueued(job)
        assertEquals(JobStatus.QUEUED, store.get("job-1")?.status)

        store.markRunning("job-1")
        assertEquals(JobStatus.RUNNING, store.get("job-1")?.status)

        store.markSucceeded(jobId = "job-1", payRunId = "PR", finalStatus = "FINALIZED")
        assertEquals(JobStatus.SUCCEEDED, store.get("job-1")?.status)

        store.markFailed(jobId = "job-2", error = "boom")
        assertEquals(JobStatus.FAILED, store.get("job-2")?.status)
    }
}
