package com.example.uspayroll.filings.kafka

import com.example.uspayroll.filings.persistence.PaycheckLedgerRepository
import com.example.uspayroll.filings.persistence.PaycheckPaymentStatusRepository
import com.example.uspayroll.messaging.events.payments.PaycheckPaymentStatusChangedEvent
import com.example.uspayroll.messaging.events.reporting.PaycheckLedgerEvent
import com.example.uspayroll.messaging.inbox.JdbcEventInbox
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
) {

    private val logger = LoggerFactory.getLogger(FilingsKafkaConsumer::class.java)

    @KafkaListener(
        topics = ["\${filings.kafka.paycheck-ledger-topic:paycheck.ledger}"],
        groupId = "\${filings.kafka.group-id:filings-service}",
    )
    fun onPaycheckLedger(record: ConsumerRecord<String, String>) {
        val payload = record.value()
        val json: JsonNode = objectMapper.readTree(payload)
        val headerEventId = record.headers().lastHeader("X-Event-Id")?.value()?.toString(Charsets.UTF_8)
        val eventId = headerEventId ?: json.get("eventId")?.asText()

        if (eventId == null) {
            logger.warn(
                "kafka.event.missing_event_id topic={} partition={} offset={} key={} type={}",
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                "PaycheckLedger",
            )
            return
        }

        inbox.runIfFirst(props.consumerName, eventId) {
            val evt = objectMapper.readValue(payload, PaycheckLedgerEvent::class.java)
            ledger.upsertFromEvent(evt)
            logger.info(
                "kafka.event.processed consumer={} event_id={} topic={} key={} type={} paycheck_id={} action={}",
                props.consumerName,
                eventId,
                record.topic(),
                record.key(),
                "PaycheckLedger",
                evt.paycheckId,
                evt.action.name,
            )
        }
    }

    @KafkaListener(
        topics = ["\${filings.kafka.payment-status-changed-topic:paycheck.payment.status_changed}"],
        groupId = "\${filings.kafka.group-id:filings-service}",
    )
    fun onPaymentStatusChanged(record: ConsumerRecord<String, String>) {
        val payload = record.value()
        val json: JsonNode = objectMapper.readTree(payload)
        val headerEventId = record.headers().lastHeader("X-Event-Id")?.value()?.toString(Charsets.UTF_8)
        val eventId = headerEventId ?: json.get("eventId")?.asText()

        if (eventId == null) {
            logger.warn(
                "kafka.event.missing_event_id topic={} partition={} offset={} key={} type={}",
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                "PaycheckPaymentStatusChanged",
            )
            return
        }

        inbox.runIfFirst(props.consumerName, eventId) {
            val evt = objectMapper.readValue(payload, PaycheckPaymentStatusChangedEvent::class.java)
            paymentStatuses.upsertFromEvent(evt)
            logger.info(
                "kafka.event.processed consumer={} event_id={} topic={} key={} type={} paycheck_id={} status={}",
                props.consumerName,
                eventId,
                record.topic(),
                record.key(),
                "PaycheckPaymentStatusChanged",
                evt.paycheckId,
                evt.status.name,
            )
        }
    }
}
