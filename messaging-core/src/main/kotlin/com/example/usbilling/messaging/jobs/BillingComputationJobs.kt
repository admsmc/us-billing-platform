package com.example.usbilling.messaging.jobs

/**
 * Work item for computing and finalizing a single customer bill.
 *
 * Published by billing-orchestrator-service when a bill needs computation.
 * Consumed by billing-worker-service to perform the calculation.
 *
 * Delivery is assumed at-least-once; consumers must be idempotent.
 */
data class ComputeBillJob(
    val messageId: String,
    val billId: String,
    val utilityId: String,
    val customerId: String,
    val billingPeriodId: String,
    val serviceState: String,
    /** 1-based attempt counter at the message layer (informational). */
    val attempt: Int = 1,
)

/**
 * Result message published by worker after bill computation completes.
 *
 * Consumed by billing-orchestrator-service to persist the computed bill.
 */
data class BillComputationCompletedEvent(
    val messageId: String,
    val billId: String,
    val utilityId: String,
    val customerId: String,
    val success: Boolean,
    /** Serialized BillResult as JSON (if success=true). */
    val billResultJson: String? = null,
    /** Error message (if success=false). */
    val errorMessage: String? = null,
    /** ISO-8601 timestamp */
    val computedAt: String,
)

/**
 * Common routing keys for billing computation job flow.
 */
object BillingComputationJobRouting {
    const val EXCHANGE = "billing.jobs"

    // Main job queue
    const val COMPUTE_BILL = "billing.compute.bill"

    // Result events
    const val BILL_COMPUTED = "billing.bill.computed"

    // Retries (dead-letter back to COMPUTE_BILL)
    const val RETRY_30S = "billing.compute.bill.retry.30s"
    const val RETRY_1M = "billing.compute.bill.retry.1m"
    const val RETRY_2M = "billing.compute.bill.retry.2m"
    const val RETRY_5M = "billing.compute.bill.retry.5m"
    const val RETRY_10M = "billing.compute.bill.retry.10m"

    // DLQ
    const val DLQ = "billing.compute.bill.dlq"

    val retryRoutingKeys: List<String> = listOf(
        RETRY_30S,
        RETRY_1M,
        RETRY_2M,
        RETRY_5M,
        RETRY_10M,
    )
}
