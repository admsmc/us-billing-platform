package com.example.uspayroll.payments.events

import com.example.uspayroll.messaging.events.payments.PaymentBatchLifecycleStatus
import com.example.uspayroll.messaging.events.payments.PaymentBatchStatusChangedEvent
import com.example.uspayroll.messaging.events.payments.PaymentBatchTerminalEvent
import com.example.uspayroll.payments.outbox.OutboxRepository
import com.example.uspayroll.payments.persistence.PaymentBatchRow
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class PaymentBatchEventPublisher(
    private val props: PaymentsEventsProperties,
    private val outbox: OutboxRepository,
    private val objectMapper: ObjectMapper,
) {
    fun publishBatchStatusChanged(batch: PaymentBatchRow, now: Instant = Instant.now()) {
        val status = PaymentBatchLifecycleStatus.valueOf(batch.status.name)

        val evt = PaymentBatchStatusChangedEvent(
            eventId = "payment-batch-status-changed:${batch.employerId}:${batch.batchId}:${batch.status.name}:${batch.totalPayments}:${batch.settledPayments}:${batch.failedPayments}",
            occurredAt = now,
            employerId = batch.employerId,
            batchId = batch.batchId,
            payRunId = batch.payRunId,
            status = status,
            totalPayments = batch.totalPayments,
            settledPayments = batch.settledPayments,
            failedPayments = batch.failedPayments,
        )

        try {
            outbox.enqueue(
                topic = props.paymentBatchStatusChangedTopic,
                eventKey = "${batch.employerId}:${batch.payRunId}",
                eventType = "PaymentBatchStatusChanged",
                eventId = evt.eventId,
                aggregateId = batch.batchId,
                payloadJson = objectMapper.writeValueAsString(evt),
                now = now,
            )
        } catch (_: DataIntegrityViolationException) {
            // deterministic eventId duplicate
        }

        if (status == PaymentBatchLifecycleStatus.COMPLETED || status == PaymentBatchLifecycleStatus.FAILED) {
            publishTerminal(batch, status, now)
        }
    }

    private fun publishTerminal(batch: PaymentBatchRow, status: PaymentBatchLifecycleStatus, now: Instant) {
        val evt = PaymentBatchTerminalEvent(
            eventId = "payment-batch-terminal:${batch.employerId}:${batch.batchId}:${status.name}",
            occurredAt = now,
            employerId = batch.employerId,
            batchId = batch.batchId,
            payRunId = batch.payRunId,
            status = status,
            totalPayments = batch.totalPayments,
            settledPayments = batch.settledPayments,
            failedPayments = batch.failedPayments,
        )

        try {
            outbox.enqueue(
                topic = props.paymentBatchTerminalTopic,
                eventKey = "${batch.employerId}:${batch.payRunId}",
                eventType = "PaymentBatchTerminal",
                eventId = evt.eventId,
                aggregateId = batch.batchId,
                payloadJson = objectMapper.writeValueAsString(evt),
                now = now,
            )
        } catch (_: DataIntegrityViolationException) {
            // deterministic eventId duplicate
        }
    }
}
