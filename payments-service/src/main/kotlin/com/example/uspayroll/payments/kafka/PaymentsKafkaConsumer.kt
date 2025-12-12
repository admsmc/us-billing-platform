package com.example.uspayroll.payments.kafka

import com.example.uspayroll.messaging.events.payments.PaycheckPaymentRequestedEvent
import com.example.uspayroll.messaging.inbox.JdbcEventInbox
import com.example.uspayroll.payments.service.PaymentIntakeService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
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
) {
    private val logger = LoggerFactory.getLogger(PaymentsKafkaConsumer::class.java)

    @KafkaListener(
        topics = ["\${payments.kafka.payment-requested-topic:paycheck.payment.requested}"],
        groupId = "\${payments.kafka.group-id:payments-service}",
    )
    fun onPaycheckPaymentRequested(record: ConsumerRecord<String, String>) {
        val headerEventId = record.headers().lastHeader("X-Event-Id")?.value()?.toString(Charsets.UTF_8)
        val headerEventType = record.headers().lastHeader("X-Event-Type")?.value()?.toString(Charsets.UTF_8)

        val payload = record.value()
        val json: JsonNode = objectMapper.readTree(payload)

        val eventId = headerEventId ?: json.get("eventId")?.asText()
        val eventType = headerEventType ?: "PaycheckPaymentRequested"

        if (eventId == null) {
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

        val first = inbox.tryMarkProcessed(props.consumerName, eventId)
        if (!first) {
            logger.info(
                "kafka.event.duplicate_ignored consumer={} event_id={} topic={} partition={} offset={} type={}",
                props.consumerName,
                eventId,
                record.topic(),
                record.partition(),
                record.offset(),
                eventType,
            )
            return
        }

        val evt = objectMapper.treeToValue(json, PaycheckPaymentRequestedEvent::class.java)
        intakeService.handlePaymentRequested(evt)
    }
}
