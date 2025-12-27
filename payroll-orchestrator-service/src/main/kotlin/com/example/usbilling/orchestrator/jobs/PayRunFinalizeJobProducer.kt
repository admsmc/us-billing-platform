package com.example.usbilling.orchestrator.jobs

import com.example.usbilling.messaging.jobs.CreatePayRunItemsJob
import com.example.usbilling.messaging.jobs.FinalizePayRunEmployeeJob
import com.example.usbilling.messaging.jobs.FinalizePayRunJobRouting
import com.example.usbilling.orchestrator.outbox.OutboxDestinationType
import com.example.usbilling.orchestrator.outbox.OutboxRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@ConfigurationProperties(prefix = "orchestrator.jobs.rabbit")
data class OrchestratorRabbitJobsProperties(
    /** When enabled, startFinalize will enqueue per-employee jobs to RabbitMQ via outbox. */
    var enabled: Boolean = false,
    var exchange: String = FinalizePayRunJobRouting.EXCHANGE,
    var finalizeEmployeeRoutingKey: String = FinalizePayRunJobRouting.FINALIZE_EMPLOYEE,
    var createItemsRoutingKey: String = FinalizePayRunJobRouting.CREATE_ITEMS,
)

@Service
@EnableConfigurationProperties(OrchestratorRabbitJobsProperties::class)
class PayRunFinalizeJobProducer(
    private val props: OrchestratorRabbitJobsProperties,
    private val outbox: OutboxRepository,
    private val objectMapper: ObjectMapper,
) {

    /**
     * Transactionally enqueue one job per employee (idempotent via deterministic eventId).
     */
    @Transactional
    fun enqueueFinalizeEmployeeJobs(
        employerId: String,
        payRunId: String,
        payPeriodId: String,
        runType: String,
        runSequence: Int,
        paycheckIdsByEmployeeId: Map<String, String>,
        earningOverridesByEmployeeId: Map<String, List<com.example.uspayroll.messaging.jobs.PayRunEarningOverrideJob>> = emptyMap(),
        now: Instant = Instant.now(),
    ): Int {
        if (!props.enabled) return 0
        if (paycheckIdsByEmployeeId.isEmpty()) return 0

        val rows = paycheckIdsByEmployeeId.entries.map { (employeeId, paycheckId) ->
            val msg = FinalizePayRunEmployeeJob(
                messageId = "msg-${UUID.randomUUID()}",
                employerId = employerId,
                payRunId = payRunId,
                payPeriodId = payPeriodId,
                runType = runType,
                runSequence = runSequence,
                employeeId = employeeId,
                paycheckId = paycheckId,
                earningOverrides = earningOverridesByEmployeeId[employeeId] ?: emptyList(),
                attempt = 1,
            )

            val eventId = "job-finalize-employee:$employerId:$payRunId:$employeeId"

            OutboxRepository.PendingOutboxInsert(
                // For Rabbit destinations, we interpret:
                // - topic as exchange
                // - eventKey as routing key
                topic = props.exchange,
                eventKey = props.finalizeEmployeeRoutingKey,
                eventType = "FinalizePayRunEmployeeJob",
                eventId = eventId,
                aggregateId = "$employerId:$payRunId",
                payloadJson = objectMapper.writeValueAsString(msg),
                destinationType = OutboxDestinationType.RABBIT,
            )
        }

        // Insert individually to preserve idempotency behavior on unique event_id.
        // (Batch insert would fail the whole batch if a single duplicate exists.)
        var inserted = 0
        rows.forEach { row ->
            try {
                outbox.enqueue(
                    topic = row.topic,
                    eventKey = row.eventKey,
                    eventType = row.eventType,
                    eventId = row.eventId,
                    aggregateId = row.aggregateId,
                    payloadJson = row.payloadJson,
                    destinationType = row.destinationType,
                    now = now,
                )
                inserted += 1
            } catch (_: DataIntegrityViolationException) {
                // Duplicate eventId => already enqueued.
            }
        }

        return inserted
    }

    /**
     * Transactionally enqueue bulk item creation job (async-first pattern).
     *
     * This job will chunk employeeIds and insert pay_run_item rows in batches,
     * then publish per-employee finalize jobs.
     */
    @Transactional
    fun enqueueCreateItemsJob(
        employerId: String,
        payRunId: String,
        payPeriodId: String,
        runType: String,
        runSequence: Int,
        employeeIds: List<String>,
        earningOverridesByEmployeeId: Map<String, List<com.example.uspayroll.messaging.jobs.PayRunEarningOverrideJob>> = emptyMap(),
        chunkSize: Int = 2000,
        now: Instant = Instant.now(),
    ): Boolean {
        if (!props.enabled) return false

        val msg = CreatePayRunItemsJob(
            messageId = "msg-${UUID.randomUUID()}",
            employerId = employerId,
            payRunId = payRunId,
            payPeriodId = payPeriodId,
            runType = runType,
            runSequence = runSequence,
            employeeIds = employeeIds,
            earningOverridesByEmployeeId = earningOverridesByEmployeeId,
            chunkSize = chunkSize,
        )

        // Use deterministic eventId for idempotency.
        val eventId = "job-create-items:$employerId:$payRunId"

        try {
            outbox.enqueue(
                topic = props.exchange,
                eventKey = props.createItemsRoutingKey,
                eventType = "CreatePayRunItemsJob",
                eventId = eventId,
                aggregateId = "$employerId:$payRunId",
                payloadJson = objectMapper.writeValueAsString(msg),
                destinationType = OutboxDestinationType.RABBIT,
                now = now,
            )
            return true
        } catch (_: DataIntegrityViolationException) {
            // Already enqueued.
            return false
        }
    }
}
