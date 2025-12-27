package com.example.usbilling.payments.processor

import com.example.usbilling.messaging.events.payments.PaycheckPaymentLifecycleStatus
import com.example.usbilling.messaging.events.payments.PaycheckPaymentStatusChangedEvent
import com.example.usbilling.payments.events.PaymentBatchEventPublisher
import com.example.usbilling.payments.events.PaymentsEventsProperties
import com.example.usbilling.payments.outbox.OutboxRepository
import com.example.usbilling.payments.persistence.PaycheckPaymentBatchOps
import com.example.usbilling.payments.persistence.PaycheckPaymentRepository
import com.example.usbilling.payments.persistence.PaymentBatchRepository
import com.example.usbilling.payments.persistence.PaymentBatchStatus
import com.example.usbilling.payments.provider.PaymentBatchSubmissionRequest
import com.example.usbilling.payments.provider.PaymentInstruction
import com.example.usbilling.payments.provider.PaymentProvider
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@ConfigurationProperties(prefix = "payments.processor")
data class PaymentsProcessorProperties(
    var enabled: Boolean = false,
    /** Max number of payments to process per tick (across all batches). */
    var batchSize: Int = 100,
    /** Max number of batches to claim per tick. */
    var maxBatchesPerTick: Int = 25,
    var lockOwner: String = "payments-processor",
    var lockTtlSeconds: Long = 60,
    var fixedDelayMillis: Long = 1_000L,
)

@Configuration
@EnableScheduling
@EnableConfigurationProperties(PaymentsProcessorProperties::class)
class PaymentsProcessorConfig

@Component
@ConditionalOnProperty(prefix = "payments.processor", name = ["enabled"], havingValue = "true")
class PaymentsProcessor(
    private val props: PaymentsProcessorProperties,
    private val batchRepository: PaymentBatchRepository,
    private val batchOps: PaycheckPaymentBatchOps,
    private val payments: PaycheckPaymentRepository,
    private val paymentProvider: PaymentProvider,
    private val outbox: OutboxRepository,
    private val events: PaymentsEventsProperties,
    private val batchEvents: PaymentBatchEventPublisher,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(PaymentsProcessor::class.java)

    @Scheduled(fixedDelayString = "\${payments.processor.fixed-delay-millis:1000}")
    fun tick() {
        tickOnce()
    }

    fun tickOnce(now: Instant = Instant.now()): Int {
        val lockTtl = Duration.ofSeconds(props.lockTtlSeconds.coerceAtLeast(5L))

        val batches = batchRepository.claimActiveBatches(
            limit = props.maxBatchesPerTick,
            lockOwner = props.lockOwner,
            lockTtl = lockTtl,
            now = now,
        )

        if (batches.isEmpty()) return 0

        var processed = 0

        batches.forEach { batch ->
            if (processed >= props.batchSize) return@forEach

            val toProcess = batchOps.claimCreatedByBatch(
                employerId = batch.employerId,
                batchId = batch.batchId,
                limit = (props.batchSize - processed).coerceAtLeast(1),
                lockOwner = props.lockOwner,
                lockTtl = lockTtl,
                now = now,
            )

            // Emit SUBMITTED for claimed rows.
            toProcess.forEach { row ->
                enqueueStatusChanged(row.employerId, row.payRunId, row.paycheckId, row.paymentId, PaycheckPaymentLifecycleStatus.SUBMITTED, now)
            }

            // Delegate to the provider seam.
            val submission = paymentProvider.submitBatch(
                PaymentBatchSubmissionRequest(
                    employerId = batch.employerId,
                    payRunId = batch.payRunId,
                    batchId = batch.batchId,
                    payments = toProcess.map { row ->
                        PaymentInstruction(
                            paymentId = row.paymentId,
                            paycheckId = row.paycheckId,
                            employeeId = row.employeeId,
                            payPeriodId = row.payPeriodId,
                            currency = row.currency,
                            netCents = row.netCents,
                            attempts = row.attempts,
                        )
                    },
                ),
                now = now,
            )

            // Best-effort: persist provider batch ref.
            if (!submission.providerBatchRef.isNullOrBlank()) {
                batchRepository.setProviderBatchRefIfMissing(batch.employerId, batch.batchId, submission.providerBatchRef)
            }

            val byPaymentId = submission.paymentResults.associateBy { it.paymentId }

            toProcess.forEach { row ->
                val result = byPaymentId[row.paymentId]

                if (result?.providerPaymentRef != null) {
                    payments.setProviderPaymentRefIfMissing(row.employerId, row.paymentId, result.providerPaymentRef)
                }

                val terminal = result?.terminalStatus
                if (terminal == PaycheckPaymentLifecycleStatus.SETTLED || terminal == PaycheckPaymentLifecycleStatus.FAILED) {
                    payments.updateStatus(
                        employerId = row.employerId,
                        paymentId = row.paymentId,
                        status = terminal,
                        error = result.error,
                        now = now,
                    )

                    enqueueStatusChanged(row.employerId, row.payRunId, row.paycheckId, row.paymentId, terminal, now)
                }
            }

            processed += toProcess.size

            // Always reconcile batch state/counters after processing.
            val reconciled = batchRepository.reconcileBatch(batch.employerId, batch.batchId)

            // Ensure terminal batch lifecycle events are emitted (COMPLETED/FAILED).
            if (reconciled != null && (reconciled.status == PaymentBatchStatus.COMPLETED || reconciled.status == PaymentBatchStatus.FAILED)) {
                batchEvents.publishBatchStatusChanged(reconciled, now = now)
            }
        }

        logger.info(
            "payments.processor.processed payments={} batches={} provider={}",
            processed,
            batches.size,
            paymentProvider.providerName,
        )

        return processed
    }

    private fun enqueueStatusChanged(employerId: String, payRunId: String, paycheckId: String, paymentId: String, status: PaycheckPaymentLifecycleStatus, now: Instant) {
        val evt = PaycheckPaymentStatusChangedEvent(
            eventId = "paycheck-payment-status-changed:$employerId:$paycheckId:${status.name}",
            occurredAt = now,
            employerId = employerId,
            payRunId = payRunId,
            paycheckId = paycheckId,
            paymentId = paymentId,
            status = status,
        )

        try {
            outbox.enqueue(
                topic = events.paymentStatusChangedTopic,
                eventKey = "$employerId:$payRunId",
                eventType = "PaycheckPaymentStatusChanged",
                eventId = evt.eventId,
                aggregateId = paycheckId,
                payloadJson = objectMapper.writeValueAsString(evt),
                now = now,
            )
        } catch (_: DataIntegrityViolationException) {
            // Deterministic eventId duplicate -> ignore.
        }
    }
}
