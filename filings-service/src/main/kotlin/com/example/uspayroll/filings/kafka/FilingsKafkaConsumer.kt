package com.example.uspayroll.filings.kafka

import com.example.uspayroll.filings.persistence.PaycheckLedgerRepository
import com.example.uspayroll.filings.persistence.PaycheckPaymentStatusRepository
import com.example.uspayroll.messaging.events.payments.PaycheckPaymentStatusChangedEvent
import com.example.uspayroll.messaging.events.reporting.PaycheckLedgerEvent
import com.example.uspayroll.messaging.inbox.JdbcEventInbox
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@ConfigurationProperties(prefix = "filings.kafka")
data class FilingsKafkaProperties(
    var enabled: Boolean = false,
    var consumerName: String = "filings-service",
    var groupId: String = "filings-service",
    var paycheckLedgerTopic: String = "paycheck.ledger",
    var paymentStatusChangedTopic: String = "paycheck.payment.status_changed",
)

@Configuration
@EnableConfigurationProperties(FilingsKafkaProperties::class)
class FilingsKafkaConfig {

    @Bean
    fun filingsEventInbox(dataSource: DataSource): JdbcEventInbox = JdbcEventInbox(dataSource)
}

@Component
@ConditionalOnProperty(prefix = "filings.kafka", name = ["enabled"], havingValue = "true")
class FilingsKafkaConsumer(
    private val props: FilingsKafkaProperties,
    private val objectMapper: ObjectMapper,
    private val inbox: JdbcEventInbox,
    private val ledger: PaycheckLedgerRepository,
    private val paymentStatuses: PaycheckPaymentStatusRepository,
    private val meterRegistry: MeterRegistry,
) {

    private val logger = LoggerFactory.getLogger(FilingsKafkaConsumer::class.java)

    @KafkaListener(
        topics = ["\${filings.kafka.paycheck-ledger-topic:paycheck.ledger}"],
        groupId = "\${filings.kafka.group-id:filings-service}",
    )
    @Suppress("TooGenericExceptionCaught")
    fun onPaycheckLedger(record: ConsumerRecord<String, String>) {
        val startNanos = System.nanoTime()
        val payload = record.value()
        val json: JsonNode = objectMapper.readTree(payload)
        val headerEventId = record.headers().lastHeader("X-Event-Id")?.value()?.toString(Charsets.UTF_8)
        val eventId = headerEventId ?: json.get("eventId")?.asText()

        val eventType = "PaycheckLedger"
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
                val evt = objectMapper.readValue(payload, PaycheckLedgerEvent::class.java)
                ledger.upsertFromEvent(evt)
                logger.info(
                    "kafka.event.processed consumer={} event_id={} topic={} key={} type={} paycheck_id={} action={}",
                    props.consumerName,
                    eventId,
                    record.topic(),
                    record.key(),
                    eventType,
                    evt.paycheckId,
                    evt.action.name,
                )
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

    @KafkaListener(
        topics = ["\${filings.kafka.payment-status-changed-topic:paycheck.payment.status_changed}"],
        groupId = "\${filings.kafka.group-id:filings-service}",
    )
    @Suppress("TooGenericExceptionCaught")
    fun onPaymentStatusChanged(record: ConsumerRecord<String, String>) {
        val startNanos = System.nanoTime()
        val payload = record.value()
        val json: JsonNode = objectMapper.readTree(payload)
        val headerEventId = record.headers().lastHeader("X-Event-Id")?.value()?.toString(Charsets.UTF_8)
        val eventId = headerEventId ?: json.get("eventId")?.asText()

        val eventType = "PaycheckPaymentStatusChanged"
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
                val evt = objectMapper.readValue(payload, PaycheckPaymentStatusChangedEvent::class.java)
                paymentStatuses.upsertFromEvent(evt)
                logger.info(
                    "kafka.event.processed consumer={} event_id={} topic={} key={} type={} paycheck_id={} status={}",
                    props.consumerName,
                    eventId,
                    record.topic(),
                    record.key(),
                    eventType,
                    evt.paycheckId,
                    evt.status.name,
                )
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
