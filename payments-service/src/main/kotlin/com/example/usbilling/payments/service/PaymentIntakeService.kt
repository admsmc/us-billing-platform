package com.example.usbilling.payments.service

import com.example.usbilling.messaging.events.payments.PaycheckPaymentLifecycleStatus
import com.example.usbilling.messaging.events.payments.PaycheckPaymentRequestedEvent
import com.example.usbilling.messaging.events.payments.PaycheckPaymentStatusChangedEvent
import com.example.usbilling.payments.events.PaymentsEventsProperties
import com.example.usbilling.payments.outbox.OutboxRepository
import com.example.usbilling.payments.persistence.PaycheckPaymentBatchOps
import com.example.usbilling.payments.persistence.PaycheckPaymentRepository
import com.example.usbilling.payments.persistence.PaymentBatchRepository
import com.example.usbilling.payments.provider.PaymentProvider
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class PaymentIntakeService(
    private val payments: PaycheckPaymentRepository,
    private val paymentBatchRepository: PaymentBatchRepository,
    private val paymentProvider: PaymentProvider,
    private val batchOps: PaycheckPaymentBatchOps,
    private val outbox: OutboxRepository,
    private val props: PaymentsEventsProperties,
    private val objectMapper: ObjectMapper,
) {

    @Transactional
    fun handlePaymentRequested(evt: PaycheckPaymentRequestedEvent) {
        val paymentId = "pmt-${evt.paycheckId}"
        val now = Instant.now()

        val provider = paymentProvider.providerName

        val batchId = paymentBatchRepository.getOrCreateBatchForPayRun(
            employerId = evt.employerId,
            payRunId = evt.payRunId,
            provider = provider,
            now = now,
        )

        payments.insertIfAbsent(
            employerId = evt.employerId,
            paymentId = paymentId,
            paycheckId = evt.paycheckId,
            payRunId = evt.payRunId,
            employeeId = evt.employeeId,
            payPeriodId = evt.payPeriodId,
            currency = evt.currency,
            netCents = evt.netCents,
            batchId = batchId,
            provider = provider,
            providerPaymentRef = null,
            now = now,
        )

        // If the payment row already existed (idempotent retry), ensure batch_id is present.
        batchOps.attachBatchIfMissing(evt.employerId, evt.paycheckId, batchId)

        // Keep batch counters reasonably fresh for UI/reconciliation.
        paymentBatchRepository.reconcileBatch(evt.employerId, batchId)

        val current = payments.findByPaycheck(evt.employerId, evt.paycheckId) ?: return

        // Emit CREATED once (idempotent via deterministic eventId + outbox unique constraint).
        if (current.status == PaycheckPaymentLifecycleStatus.CREATED) {
            val statusEvent = PaycheckPaymentStatusChangedEvent(
                eventId = "paycheck-payment-status-changed:${evt.employerId}:${evt.paycheckId}:CREATED",
                occurredAt = now,
                employerId = evt.employerId,
                payRunId = evt.payRunId,
                paycheckId = evt.paycheckId,
                paymentId = current.paymentId,
                status = PaycheckPaymentLifecycleStatus.CREATED,
            )

            try {
                outbox.enqueue(
                    topic = props.paymentStatusChangedTopic,
                    eventKey = "${evt.employerId}:${evt.payRunId}",
                    eventType = "PaycheckPaymentStatusChanged",
                    eventId = statusEvent.eventId,
                    aggregateId = evt.paycheckId,
                    payloadJson = objectMapper.writeValueAsString(statusEvent),
                    now = now,
                )
            } catch (_: DataIntegrityViolationException) {
                // Duplicate deterministic eventId -> ignore.
            }
        }
    }
}
