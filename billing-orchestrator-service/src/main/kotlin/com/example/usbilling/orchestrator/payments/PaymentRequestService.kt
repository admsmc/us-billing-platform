package com.example.usbilling.orchestrator.payments

import com.example.usbilling.messaging.events.payments.PaycheckPaymentRequestedEvent
import com.example.usbilling.orchestrator.outbox.OutboxRepository
import com.example.usbilling.orchestrator.payments.persistence.PayRunPaycheckQueryRepository
import com.example.usbilling.orchestrator.payments.persistence.PaycheckPaymentRepository
import com.example.usbilling.orchestrator.persistence.PaycheckLifecycleRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class PaymentRequestService(
    private val props: OrchestratorPaymentsProperties,
    private val paychecks: PayRunPaycheckQueryRepository,
    private val paycheckLifecycleRepository: PaycheckLifecycleRepository,
    private val paycheckPayments: PaycheckPaymentRepository,
    private val outbox: OutboxRepository,
    private val objectMapper: ObjectMapper,
) {
    data class Result(
        val candidates: Int,
        val enqueued: Int,
    )

    /**
     * Idempotently enqueue payment requests (one per succeeded paycheck).
     *
     * Idempotency strategy:
     * - deterministic eventId: paycheck-payment-requested:<employer>:<paycheck>
     * - outbox_event should have a unique constraint on event_id so duplicates are a no-op
     */
    @Transactional
    fun requestPaymentsForPayRun(employerId: String, payRunId: String, paymentStatus: com.example.usbilling.orchestrator.payrun.model.PaymentStatus): Result {
        // Reflect that we are now in-flight on paychecks (projection only; payments-service is system of record).
        paycheckLifecycleRepository.setPaymentStatusForPayRun(
            employerId = employerId,
            payRunId = payRunId,
            paymentStatus = paymentStatus,
        )

        val candidates = paychecks.listSucceededPaychecksWithNet(employerId, payRunId)
        val now = Instant.now()

        var enqueued = 0
        candidates.forEach { c ->
            // Best-effort projection row for UI/reconciliation. payments-service is still the system of record.
            paycheckPayments.insertIfAbsent(
                employerId = employerId,
                paymentId = "pmt-${c.paycheckId}",
                paycheckId = c.paycheckId,
                payRunId = payRunId,
                employeeId = c.employeeId,
                payPeriodId = c.payPeriodId,
                currency = c.currency,
                netCents = c.netCents,
            )

            val evt = PaycheckPaymentRequestedEvent(
                eventId = "paycheck-payment-requested:$employerId:${c.paycheckId}",
                occurredAt = now,
                employerId = employerId,
                payRunId = payRunId,
                payPeriodId = c.payPeriodId,
                employeeId = c.employeeId,
                paycheckId = c.paycheckId,
                currency = c.currency,
                netCents = c.netCents,
            )

            try {
                outbox.enqueue(
                    topic = props.paymentRequestedTopic,
                    // Partition key per (employer, payRun) to preserve run ordering.
                    eventKey = "$employerId:$payRunId",
                    eventType = "PaycheckPaymentRequested",
                    eventId = evt.eventId,
                    aggregateId = c.paycheckId,
                    payloadJson = objectMapper.writeValueAsString(evt),
                    now = now,
                )
                enqueued += 1
            } catch (_: DataIntegrityViolationException) {
                // Duplicate event_id (idempotent retry) -> ignore.
            }
        }

        return Result(candidates = candidates.size, enqueued = enqueued)
    }
}
