package com.example.uspayroll.worker.orchestrator

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "worker.orchestrator.payrun")
data class OrchestratorPayRunJobProperties(
    /** How many items orchestrator should claim per execute call. */
    var executeBatchSize: Int = 25,

    /** Hard cap on items orchestrator should process per execute call. */
    var executeMaxItems: Int = 200,

    /** Wall-clock time limit orchestrator should respect per execute call. */
    var executeMaxMillis: Long = 2_000L,

    /** How long to consider RUNNING items stale and eligible to be requeued. */
    var requeueStaleMillis: Long = 10 * 60 * 1000L,

    /** How long to sleep between execute calls when more work remains. */
    var executeLoopSleepMillis: Long = 250L,

    /** Maximum number of execute iterations before giving up. */
    var maxExecuteIterations: Int = 600,
)
