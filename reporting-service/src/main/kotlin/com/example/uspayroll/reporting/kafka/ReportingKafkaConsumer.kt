package com.example.uspayroll.reporting.kafka

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

@ConfigurationProperties(prefix = "reporting.kafka")
data class ReportingKafkaProperties(
    var enabled: Boolean = false,
    var consumerName: String = "reporting-service",
    var groupId: String = "reporting-service",
    var payRunFinalizedTopic: String = "payrun.finalized",
    var paycheckFinalizedTopic: String = "paycheck.finalized",
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

    private fun handle(record: ConsumerRecord<String, String>, expectedType: String) {
        val headerEventId = record.headers().lastHeader("X-Event-Id")?.value()?.toString(Charsets.UTF_8)
        val headerEventType = record.headers().lastHeader("X-Event-Type")?.value()?.toString(Charsets.UTF_8)

        val payload = record.value()
        val json: JsonNode = objectMapper.readTree(payload)

        val eventId = headerEventId ?: json.get("eventId")?.asText()
        val eventType = headerEventType ?: json.get("eventType")?.asText() ?: expectedType

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

        // For now, just log. Next step is to materialize a read model for reporting.
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
