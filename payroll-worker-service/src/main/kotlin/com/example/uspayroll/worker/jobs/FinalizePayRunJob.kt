package com.example.uspayroll.worker.jobs

import java.time.Instant

data class FinalizePayRunJob(
    val jobId: String,
    val employerId: String,
    val payPeriodId: String,
    val employeeIds: List<String>,
    val requestedPayRunId: String? = null,
    val idempotencyKey: String? = null,
    val createdAt: Instant = Instant.now(),
)

enum class JobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
}

data class FinalizePayRunJobResult(
    val jobId: String,
    val status: JobStatus,
    val payRunId: String? = null,
    val finalStatus: String? = null,
    val error: String? = null,
)
