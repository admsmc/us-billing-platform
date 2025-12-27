package com.example.usbilling.reporting.kafka

import com.example.usbilling.messaging.events.reporting.PaycheckLedgerEvent
import com.example.usbilling.messaging.inbox.JdbcEventInbox
import com.example.usbilling.reporting.persistence.PayRunSummaryRepository
import com.example.usbilling.reporting.persistence.PaycheckFinalizedRepository
import com.example.usbilling.reporting.persistence.PaycheckLedgerRepository
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

@ConfigurationProperties(prefix = "reporting.kafka")
data class ReportingKafkaProperties(
    var enabled: Boolean = false,
    var consumerName: String = "reporting-service",
    var groupId: String = "reporting-service",
    var payRunFinalizedTopic: String = "payrun.finalized",
    var paycheckFinalizedTopic: String = "paycheck.finalized",
    var paycheckLedgerTopic: String = "paycheck.ledger",
)

@Configuration
@EnableConfigurationProperties(ReportingKafkaProperties::class)
class ReportingKafkaConfig {

    @Bean
    fun reportingEventInbox(dataSource: DataSource): JdbcEventInbox = JdbcEventInbox(dataSource)
}

@Component
@ConditionalOnProperty(prefix = "reporting.kafka", name = ["enabled"], havingValue = "true")
class ReportingKafkaConsumer(
    private val props: ReportingKafkaProperties,
    private val objectMapper: ObjectMapper,
    private val inbox: JdbcEventInbox,
    private val ledgerRepository: PaycheckLedgerRepository,
    private val payRunSummaryRepository: PayRunSummaryRepository,
    private val paycheckFinalizedRepository: PaycheckFinalizedRepository,
    private val meterRegistry: MeterRegistry,
) {

    private val logger = LoggerFactory.getLogger(ReportingKafkaConsumer::class.java)

    @KafkaListener(
        topics = ["\${reporting.kafka.pay-run-finalized-topic:payrun.finalized}"],
        groupId = "\${reporting.kafka.group-id:reporting-service}",
    )
    fun onPayRunFinalized(record: ConsumerRecord<String, String>) {
        handle(record, expectedType = "PayRunFinalized")
    }

    @KafkaListener(
        topics = ["\${reporting.kafka.paycheck-finalized-topic:paycheck.finalized}"],
        groupId = "\${reporting.kafka.group-id:reporting-service}",
    )
    fun onPaycheckFinalized(record: ConsumerRecord<String, String>) {
        handle(record, expectedType = "PaycheckFinalized")
    }

    @KafkaListener(
        topics = ["\${reporting.kafka.paycheck-ledger-topic:paycheck.ledger}"],
        groupId = "\${reporting.kafka.group-id:reporting-service}",
    )
    fun onPaycheckLedger(record: ConsumerRecord<String, String>) {
        handle(record, expectedType = "PaycheckLedger")
    }

    @Suppress("TooGenericExceptionCaught")
    private fun handle(record: ConsumerRecord<String, String>, expectedType: String) {
        val startNanos = System.nanoTime()

        val headerEventId = record.headers().lastHeader("X-Event-Id")?.value()?.toString(Charsets.UTF_8)
        val headerEventType = record.headers().lastHeader("X-Event-Type")?.value()?.toString(Charsets.UTF_8)

        val payload = record.value()
        val json: JsonNode = objectMapper.readTree(payload)

        val eventId = headerEventId ?: json.get("eventId")?.asText()
        val eventType = headerEventType ?: json.get("eventType")?.asText() ?: expectedType

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
                when (eventType) {
                    "PayRunFinalized" -> {
                        val event = objectMapper.readValue(payload, PayRunSummaryRepository.PayRunFinalizedProjectionEvent::class.java)
                        payRunSummaryRepository.upsertFromEvent(event)

                        logger.info(
                            "kafka.event.materialized consumer={} event_id={} topic={} key={} type={} employer={} pay_run={} status={} total={} succeeded={} failed={}",
                            props.consumerName,
                            eventId,
                            record.topic(),
                            record.key(),
                            eventType,
                            event.employerId,
                            event.payRunId,
                            event.status,
                            event.total,
                            event.succeeded,
                            event.failed,
                        )
                    }

                    "PaycheckFinalized" -> {
                        val event = objectMapper.readValue(payload, PaycheckFinalizedRepository.PaycheckFinalizedProjectionEvent::class.java)
                        paycheckFinalizedRepository.upsertFromEvent(event)

                        logger.info(
                            "kafka.event.materialized consumer={} event_id={} topic={} key={} type={} employer={} pay_run={} paycheck={} employee={}",
                            props.consumerName,
                            eventId,
                            record.topic(),
                            record.key(),
                            eventType,
                            event.employerId,
                            event.payRunId,
                            event.paycheckId,
                            event.employeeId,
                        )
                    }

                    "PaycheckLedger" -> {
                        val event = objectMapper.readValue(payload, PaycheckLedgerEvent::class.java)
                        ledgerRepository.upsertFromEvent(event)
                        paycheckFinalizedRepository.enrichFromLedgerEvent(event)

                        logger.info(
                            "kafka.event.materialized consumer={} event_id={} topic={} key={} type={} employer={} employee={} pay_run={} paycheck={} action={} gross_cents={} net_cents={}",
                            props.consumerName,
                            eventId,
                            record.topic(),
                            record.key(),
                            eventType,
                            event.employerId,
                            event.employeeId,
                            event.payRunId,
                            event.paycheckId,
                            event.action.name,
                            event.grossCents,
                            event.netCents,
                        )
                    }

                    else -> {
                        // Keep the marker, but don't materialize yet.
                        logger.info(
                            "kafka.event.processed consumer={} event_id={} topic={} key={} type={} payload_size={}",
                            props.consumerName,
                            eventId,
                            record.topic(),
                            record.key(),
                            eventType,
                            payload.length,
                        )
                    }
                }
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
