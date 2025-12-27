package com.example.usbilling.messaging.events.payments

import java.time.Instant

enum class PaymentBatchLifecycleStatus {
    CREATED,
    PROCESSING,
    COMPLETED,
    PARTIALLY_COMPLETED,
    FAILED,
}

data class PaymentBatchStatusChangedEvent(
    val eventId: String,
    val occurredAt: Instant,
    val employerId: String,
    val batchId: String,
    val payRunId: String,
    val status: PaymentBatchLifecycleStatus,
    val totalPayments: Int,
    val settledPayments: Int,
    val failedPayments: Int,
)

/**
 * Emitted only when a batch reaches a terminal state (COMPLETED or FAILED).
 * Intended for ops/reporting indexing.
 */
data class PaymentBatchTerminalEvent(
    val eventId: String,
    val occurredAt: Instant,
    val employerId: String,
    val batchId: String,
    val payRunId: String,
    val status: PaymentBatchLifecycleStatus,
    val totalPayments: Int,
    val settledPayments: Int,
    val failedPayments: Int,
)
