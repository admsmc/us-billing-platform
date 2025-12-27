package com.example.usbilling.messaging.events.payments

import java.time.Instant

enum class PaycheckPaymentLifecycleStatus {
    CREATED,
    SUBMITTED,
    SETTLED,
    FAILED,
}

/**
 * Request that payments-service create (idempotently) and process a payment for a paycheck.
 * One event is emitted per paycheck.
 */
data class PaycheckPaymentRequestedEvent(
    val eventId: String,
    val occurredAt: Instant,
    val employerId: String,
    val payRunId: String,
    val payPeriodId: String,
    val employeeId: String,
    val paycheckId: String,
    val currency: String,
    val netCents: Long,
)

/**
 * Emitted by payments-service whenever a payment changes lifecycle status.
 */
data class PaycheckPaymentStatusChangedEvent(
    val eventId: String,
    val occurredAt: Instant,
    val employerId: String,
    val payRunId: String,
    val paycheckId: String,
    val paymentId: String,
    val status: PaycheckPaymentLifecycleStatus,
)
