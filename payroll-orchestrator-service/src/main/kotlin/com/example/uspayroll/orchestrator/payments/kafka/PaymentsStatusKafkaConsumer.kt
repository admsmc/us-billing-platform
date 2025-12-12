package com.example.uspayroll.orchestrator.payments.kafka

import com.example.uspayroll.messaging.events.payments.PaycheckPaymentStatusChangedEvent
import com.example.uspayroll.messaging.inbox.JdbcEventInbox
import com.example.uspayroll.orchestrator.payments.OrchestratorPaymentsProperties
import com.example.uspayroll.orchestrator.payments.PaymentStatusProjectionService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
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
) {
    private val logger = LoggerFactory.getLogger(PaymentsStatusKafkaConsumer::class.java)

    @KafkaListener(
        topics = ["\${orchestrator.payments.payment-status-changed-topic:paycheck.payment.status_changed}"],
        groupId = "\${orchestrator.payments.group-id:payroll-orchestrator-payments}",
    )
    fun onPaymentStatusChanged(record: ConsumerRecord<String, String>) {
        val headerEventId = record.headers().lastHeader("X-Event-Id")?.value()?.toString(Charsets.UTF_8)
        val payload = record.value()
        val json: JsonNode = objectMapper.readTree(payload)

        val eventId = headerEventId ?: json.get("eventId")?.asText()
        if (eventId == null) {
            logger.warn(
                "kafka.event.missing_event_id topic={} partition={} offset={} key={} type=PaycheckPaymentStatusChanged",
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
            )
            return
        }

        val first = inbox.tryMarkProcessed(props.consumerName, eventId)
        if (!first) return

        val evt = objectMapper.treeToValue(json, PaycheckPaymentStatusChangedEvent::class.java)
        projectionService.applyPaymentStatusChanged(evt)
    }
}
