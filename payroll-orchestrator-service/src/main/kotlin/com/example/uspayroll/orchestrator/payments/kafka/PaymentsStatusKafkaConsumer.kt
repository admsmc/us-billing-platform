package com.example.uspayroll.orchestrator.payments.kafka

import com.example.uspayroll.messaging.events.payments.PaycheckPaymentStatusChangedEvent
import com.example.uspayroll.messaging.inbox.JdbcEventInbox
import com.example.uspayroll.orchestrator.payments.OrchestratorPaymentsProperties
import com.example.uspayroll.orchestrator.payments.PaymentStatusProjectionService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@Configuration
class OrchestratorPaymentsInboxConfig {
    @Bean
    fun orchestratorPaymentsEventInbox(dataSource: DataSource): JdbcEventInbox = JdbcEventInbox(dataSource)
}

@Component
@ConditionalOnProperty(prefix = "orchestrator.payments", name = ["enabled"], havingValue = "true")
class PaymentsStatusKafkaConsumer(
    private val props: OrchestratorPaymentsProperties,
    private val objectMapper: ObjectMapper,
    private val inbox: JdbcEventInbox,
    private val projectionService: PaymentStatusProjectionService,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(PaymentsStatusKafkaConsumer::class.java)

    @KafkaListener(
        topics = ["\${orchestrator.payments.payment-status-changed-topic:paycheck.payment.status_changed}"],
        groupId = "\${orchestrator.payments.group-id:payroll-orchestrator-payments}",
    )
    @Suppress("TooGenericExceptionCaught")
    fun onPaymentStatusChanged(record: ConsumerRecord<String, String>) {
        val startNanos = System.nanoTime()
        val headerEventId = record.headers().lastHeader("X-Event-Id")?.value()?.toString(Charsets.UTF_8)
        val payload = record.value()
        val json: JsonNode = objectMapper.readTree(payload)

        val eventType = "PaycheckPaymentStatusChanged"
        val eventId = headerEventId ?: json.get("eventId")?.asText()

        var outcome = "unknown"

        try {
            if (eventId == null) {
                outcome = "missing_event_id"
                meterRegistry.counter(
                    "uspayroll.kafka.consumer.missing_event_id",
                    "consumer",
                    props.consumerName,
                    "topic",
                    record.topic(),
                    "type",
                    eventType,
                ).increment()

                logger.warn(
                    "kafka.event.missing_event_id topic={} partition={} offset={} key={} type={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key(),
                    eventType,
                )
                return
            }

            val processed = inbox.runIfFirst(props.consumerName, eventId) {
                val evt = objectMapper.treeToValue(json, PaycheckPaymentStatusChangedEvent::class.java)
                projectionService.applyPaymentStatusChanged(evt)
            }

            if (processed == null) {
                outcome = "duplicate"
                meterRegistry.counter(
                    "uspayroll.kafka.consumer.duplicate_ignored",
                    "consumer",
                    props.consumerName,
                    "topic",
                    record.topic(),
                    "type",
                    eventType,
                ).increment()

                logger.info(
                    "kafka.event.duplicate_ignored consumer={} event_id={} topic={} partition={} offset={} type={}",
                    props.consumerName,
                    eventId,
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    eventType,
                )
            } else {
                outcome = "processed"
                meterRegistry.counter(
                    "uspayroll.kafka.consumer.processed",
                    "consumer",
                    props.consumerName,
                    "topic",
                    record.topic(),
                    "type",
                    eventType,
                ).increment()

                logger.info(
                    "kafka.event.processed consumer={} event_id={} topic={} partition={} offset={} type={}",
                    props.consumerName,
                    eventId,
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    eventType,
                )
            }
        } catch (ex: Exception) {
            outcome = "error"
            meterRegistry.counter(
                "uspayroll.kafka.consumer.errors",
                "consumer",
                props.consumerName,
                "topic",
                record.topic(),
                "type",
                eventType,
                "exception",
                ex.javaClass.simpleName,
            ).increment()

            logger.error(
                "kafka.event.error consumer={} event_id={} topic={} partition={} offset={} type={} error={}",
                props.consumerName,
                eventId,
                record.topic(),
                record.partition(),
                record.offset(),
                eventType,
                ex.message,
                ex,
            )
            throw ex
        } finally {
            Timer.builder("uspayroll.kafka.consumer.processing")
                .tag("consumer", props.consumerName)
                .tag("topic", record.topic())
                .tag("type", eventType)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS)
        }
    }
}
