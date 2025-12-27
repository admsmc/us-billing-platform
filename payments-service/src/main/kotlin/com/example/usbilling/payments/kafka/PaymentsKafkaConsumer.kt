package com.example.usbilling.payments.kafka

import com.example.usbilling.messaging.events.payments.PaycheckPaymentRequestedEvent
import com.example.usbilling.messaging.inbox.JdbcEventInbox
import com.example.usbilling.payments.service.PaymentIntakeService
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

@ConfigurationProperties(prefix = "payments.kafka")
data class PaymentsKafkaProperties(
    var enabled: Boolean = false,
    var consumerName: String = "payments-service",
    var groupId: String = "payments-service",
    var paymentRequestedTopic: String = "paycheck.payment.requested",
    var paymentStatusChangedTopic: String = "paycheck.payment.status_changed",
)

@Configuration
@EnableConfigurationProperties(PaymentsKafkaProperties::class)
class PaymentsKafkaConfig {
    @Bean
    fun paymentsEventInbox(dataSource: DataSource): JdbcEventInbox = JdbcEventInbox(dataSource)
}

@Component
@ConditionalOnProperty(prefix = "payments.kafka", name = ["enabled"], havingValue = "true")
class PaymentsKafkaConsumer(
    private val props: PaymentsKafkaProperties,
    private val objectMapper: ObjectMapper,
    private val inbox: JdbcEventInbox,
    private val intakeService: PaymentIntakeService,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(PaymentsKafkaConsumer::class.java)

    @KafkaListener(
        topics = ["\${payments.kafka.payment-requested-topic:paycheck.payment.requested}"],
        groupId = "\${payments.kafka.group-id:payments-service}",
    )
    @Suppress("TooGenericExceptionCaught")
    fun onPaycheckPaymentRequested(record: ConsumerRecord<String, String>) {
        val startNanos = System.nanoTime()

        val headerEventId = record.headers().lastHeader("X-Event-Id")?.value()?.toString(Charsets.UTF_8)
        val headerEventType = record.headers().lastHeader("X-Event-Type")?.value()?.toString(Charsets.UTF_8)

        val payload = record.value()
        val json: JsonNode = objectMapper.readTree(payload)

        val eventId = headerEventId ?: json.get("eventId")?.asText()
        val eventType = headerEventType ?: "PaycheckPaymentRequested"

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
                val evt = objectMapper.treeToValue(json, PaycheckPaymentRequestedEvent::class.java)
                intakeService.handlePaymentRequested(evt)
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
