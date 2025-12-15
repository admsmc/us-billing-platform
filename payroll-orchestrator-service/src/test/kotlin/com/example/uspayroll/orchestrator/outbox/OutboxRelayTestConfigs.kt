package com.example.uspayroll.orchestrator.outbox

import org.mockito.Mockito
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.kafka.core.KafkaTemplate

@TestConfiguration
class KafkaOutboxRelayTestConfig {
    @Bean
    @Primary
    fun kafkaTemplate(): KafkaTemplate<String, String> {
        @Suppress("UNCHECKED_CAST")
        return Mockito.mock(KafkaTemplate::class.java) as KafkaTemplate<String, String>
    }
}

@TestConfiguration
class RabbitOutboxRelayTestConfig {
    @Bean
    @Primary
    fun rabbitTemplate(): RabbitTemplate = Mockito.mock(RabbitTemplate::class.java)
}
