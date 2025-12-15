package com.example.uspayroll.orchestrator.events.kafka

import com.example.uspayroll.orchestrator.events.PayRunEventsPublisher
import com.example.uspayroll.orchestrator.events.PayRunFinalizedEvent
import com.example.uspayroll.orchestrator.events.PaycheckFinalizedEvent
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate

@ConfigurationProperties(prefix = "orchestrator.events.kafka")
data class KafkaEventsProperties(
    var enabled: Boolean = false,
    var payRunFinalizedTopic: String = "payrun.finalized",
    var paycheckFinalizedTopic: String = "paycheck.finalized",
    var paycheckLedgerTopic: String = "paycheck.ledger",
)

@Configuration
@EnableConfigurationProperties(KafkaEventsProperties::class)
class KafkaPayRunEventsConfig {

    @Bean
    @ConditionalOnProperty(prefix = "orchestrator.events.kafka", name = ["enabled"], havingValue = "true")
    fun kafkaPayRunEventsPublisher(kafkaTemplate: KafkaTemplate<String, String>, objectMapper: ObjectMapper, props: KafkaEventsProperties): PayRunEventsPublisher =
        KafkaPayRunEventsPublisher(kafkaTemplate, objectMapper, props)
}

class KafkaPayRunEventsPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val props: KafkaEventsProperties,
) : PayRunEventsPublisher {

    override fun publishPayRunFinalized(event: PayRunFinalizedEvent) {
        val key = "${event.employerId}:${event.payRunId}"
        kafkaTemplate.send(props.payRunFinalizedTopic, key, objectMapper.writeValueAsString(event))
    }

    override fun publishPaycheckFinalized(event: PaycheckFinalizedEvent) {
        val key = "${event.employerId}:${event.paycheckId}"
        kafkaTemplate.send(props.paycheckFinalizedTopic, key, objectMapper.writeValueAsString(event))
    }
}
