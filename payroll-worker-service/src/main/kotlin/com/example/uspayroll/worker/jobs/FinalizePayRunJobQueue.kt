package com.example.uspayroll.worker.jobs

interface FinalizePayRunJobQueue {
    fun enqueue(job: FinalizePayRunJob)
    fun tryDequeue(): FinalizePayRunJob?
}

interface FinalizePayRunJobStore {
    fun initQueued(job: FinalizePayRunJob)
    fun markRunning(jobId: String)
    fun markSucceeded(jobId: String, payRunId: String, finalStatus: String)
    fun markFailed(jobId: String, error: String)
    fun get(jobId: String): FinalizePayRunJobResult?
}
