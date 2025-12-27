package com.example.usbilling.messaging.jobs

/**
 * Work item for creating pay_run_item rows in bulk for a payrun.
 *
 * This is phase 0 of the async-first finalization flow:
 * 1. Orchestrator receives POST /payruns/finalize
 * 2. Creates pay_run record with status PENDING
 * 3. Publishes CreatePayRunItemsJob to queue
 * 4. Returns 202 immediately
 * 5. Worker chunks employeeIds and inserts in batches
 * 6. Worker publishes FinalizePayRunEmployeeJob for each employee
 * 7. Background finalizer transitions PENDING -> RUNNING -> FINALIZED
 */
data class CreatePayRunItemsJob(
    val messageId: String,
    val employerId: String,
    val payRunId: String,
    val payPeriodId: String,
    val runType: String,
    val runSequence: Int,
    val employeeIds: List<String>,
    val earningOverridesByEmployeeId: Map<String, List<PayRunEarningOverrideJob>> = emptyMap(),
    /** Chunk size for batched INSERTs (default 2000). */
    val chunkSize: Int = 2000,
)

/**
 * Work item for finalizing (computing + persisting) a single employee paycheck within a payrun.
 *
 * Delivery is assumed at-least-once; consumers must be idempotent.
 */
data class FinalizePayRunEmployeeJob(
    val messageId: String,
    val employerId: String,
    val payRunId: String,
    val payPeriodId: String,
    /** Pay run type name (e.g. REGULAR, OFF_CYCLE). */
    val runType: String,
    val runSequence: Int,
    val employeeId: String,
    /**
     * Paycheck ID assigned by orchestrator for this (employer, payRun, employee) item.
     *
     * This allows the worker to compute+persist idempotently without needing the orchestrator
     * to generate IDs at execution time.
     */
    val paycheckId: String,
    /** Optional per-employee earning overrides (primarily for off-cycle runs). */
    val earningOverrides: List<PayRunEarningOverrideJob> = emptyList(),
    /** 1-based attempt counter at the message layer (informational). */
    val attempt: Int = 1,
)

/**
 * Stable, service-agnostic representation of earning overrides carried on work items.
 */
data class PayRunEarningOverrideJob(
    val code: String,
    val units: Double = 1.0,
    val rateCents: Long? = null,
    val amountCents: Long? = null,
)

/**
 * Common routing keys for finalize-payrun job flow.
 */
object FinalizePayRunJobRouting {
    const val EXCHANGE = "payrun.jobs"

    // Bulk item creation (async-first pattern)
    const val CREATE_ITEMS = "payrun.create.items"

    // Main
    const val FINALIZE_EMPLOYEE = "payrun.finalize.employee"

    // Retries (dead-letter back to FINALIZE_EMPLOYEE)
    const val RETRY_30S = "payrun.finalize.employee.retry.30s"
    const val RETRY_1M = "payrun.finalize.employee.retry.1m"
    const val RETRY_2M = "payrun.finalize.employee.retry.2m"
    const val RETRY_5M = "payrun.finalize.employee.retry.5m"
    const val RETRY_10M = "payrun.finalize.employee.retry.10m"
    const val RETRY_20M = "payrun.finalize.employee.retry.20m"
    const val RETRY_40M = "payrun.finalize.employee.retry.40m"

    // DLQ
    const val DLQ = "payrun.finalize.employee.dlq"

    val retryRoutingKeys: List<String> = listOf(RETRY_30S, RETRY_1M, RETRY_2M, RETRY_5M, RETRY_10M, RETRY_20M, RETRY_40M)
}
