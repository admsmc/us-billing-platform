package com.example.usbilling.orchestrator.events

import java.time.Instant

interface PayRunEventsPublisher {
    fun publishPayRunFinalized(event: PayRunFinalizedEvent)
    fun publishPaycheckFinalized(event: PaycheckFinalizedEvent)
}

/**
 * Emitted when a payrun reaches a terminal state.
 */
data class PayRunFinalizedEvent(
    val eventId: String,
    val occurredAt: Instant,
    val employerId: String,
    val payRunId: String,
    val payPeriodId: String,
    val status: String,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
)

/**
 * Emitted for each finalized paycheck in a payrun.
 */
data class PaycheckFinalizedEvent(
    val eventId: String,
    val occurredAt: Instant,
    val employerId: String,
    val payRunId: String,
    val paycheckId: String,
    val employeeId: String,
)
