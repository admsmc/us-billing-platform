package com.example.uspayroll.messaging.jobs

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
