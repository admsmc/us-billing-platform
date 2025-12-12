package com.example.uspayroll.orchestrator.events

import com.example.uspayroll.orchestrator.events.kafka.KafkaEventsProperties
import com.example.uspayroll.orchestrator.outbox.OutboxRepository
import com.example.uspayroll.orchestrator.payrun.model.PayRunStatus
import com.example.uspayroll.orchestrator.payrun.persistence.PayRunItemRepository
import com.example.uspayroll.orchestrator.payrun.persistence.PayRunRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class PayRunOutboxEnqueuer(
    private val payRunRepository: PayRunRepository,
    private val payRunItemRepository: PayRunItemRepository,
    private val outboxRepository: OutboxRepository,
    private val objectMapper: ObjectMapper,
    private val kafkaProps: KafkaEventsProperties,
) {

    /**
     * Transactionally:
     * 1) writes pay_run terminal status
     * 2) enqueues outbox events for payrun + each succeeded paycheck
     */
    @Transactional
    fun finalizePayRunAndEnqueueOutboxEvents(
        employerId: String,
        payRunId: String,
        payPeriodId: String,
        status: PayRunStatus,
        total: Int,
        succeeded: Int,
        failed: Int,
    ) {
        payRunRepository.setFinalStatusAndReleaseLease(
            employerId = employerId,
            payRunId = payRunId,
            status = status,
        )

        val now = Instant.now()

        val payRunFinalized = PayRunFinalizedEvent(
            eventId = "payrun-finalized:$employerId:$payRunId",
            occurredAt = now,
            employerId = employerId,
            payRunId = payRunId,
            payPeriodId = payPeriodId,
            status = status.name,
            total = total,
            succeeded = succeeded,
            failed = failed,
        )

        outboxRepository.enqueue(
            topic = kafkaProps.payRunFinalizedTopic,
            // Partition key: per (employer, payRun) to preserve ordering for a run.
            eventKey = "${employerId}:${payRunId}",
            eventType = "PayRunFinalized",
            eventId = payRunFinalized.eventId,
            aggregateId = payRunId,
            payloadJson = objectMapper.writeValueAsString(payRunFinalized),
            now = now,
        )

        val succeededPaychecks = payRunItemRepository.listSucceededPaychecks(employerId, payRunId)

        val paycheckRows = succeededPaychecks.map { (employeeId, paycheckId) ->
            val evt = PaycheckFinalizedEvent(
                eventId = "paycheck-finalized:$employerId:$paycheckId",
                occurredAt = now,
                employerId = employerId,
                payRunId = payRunId,
                paycheckId = paycheckId,
                employeeId = employeeId,
            )

            OutboxRepository.PendingOutboxInsert(
                topic = kafkaProps.paycheckFinalizedTopic,
                // Partition key: per (employer, payRun) to keep paychecks for a run ordered.
                eventKey = "${employerId}:${payRunId}",
                eventType = "PaycheckFinalized",
                eventId = evt.eventId,
                aggregateId = paycheckId,
                payloadJson = objectMapper.writeValueAsString(evt),
            )
        }

        outboxRepository.enqueueBatch(paycheckRows, now = now)
    }
}
