package com.example.uspayroll.orchestrator.outbox

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.support.MessageBuilder
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@ConfigurationProperties(prefix = "orchestrator.outbox.relay")
data class OutboxRelayProperties(
    var enabled: Boolean = false,
    var batchSize: Int = 100,
    var lockOwner: String = "outbox-relay",
    var lockTtlSeconds: Long = 60,
    /** Fixed delay between relay runs. */
    var fixedDelayMillis: Long = 1_000L,
)

@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxRelayProperties::class)
class OutboxRelayConfig

@Component
@ConditionalOnProperty(prefix = "orchestrator.outbox.relay", name = ["enabled"], havingValue = "true")
@ConditionalOnBean(KafkaTemplate::class)
class OutboxRelay(
    private val props: OutboxRelayProperties,
    private val outboxRepository: OutboxRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
) {

    private val logger = LoggerFactory.getLogger(OutboxRelay::class.java)

    @Scheduled(fixedDelayString = "\${orchestrator.outbox.relay.fixed-delay-millis:1000}")
    fun tick() {
        val lockTtl = Duration.ofSeconds(props.lockTtlSeconds.coerceAtLeast(5L))
        val claimed = outboxRepository.claimBatch(
            limit = props.batchSize,
            lockOwner = props.lockOwner,
            lockTtl = lockTtl,
        )

        if (claimed.isEmpty()) return

        claimed.forEach { row ->
            try {
                val msg = MessageBuilder.withPayload(row.payloadJson)
                    .setHeader(KafkaHeaders.TOPIC, row.topic)
                    .setHeader(KafkaHeaders.KEY, row.eventKey)
                    .apply {
                        if (row.eventId != null) {
                            setHeader("X-Event-Id", row.eventId)
                        }
                        setHeader("X-Event-Type", row.eventType)
                        if (row.aggregateId != null) {
                            setHeader("X-Aggregate-Id", row.aggregateId)
                        }
                    }
                    .build()

                kafkaTemplate.send(msg).get()
                outboxRepository.markSent(row.outboxId)
            } catch (t: Throwable) {
                val next = computeNextAttempt(row.attempts)
                logger.warn(
                    "outbox.publish.failed outbox_id={} topic={} attempts={} next_delay_ms={} err={}",
                    row.outboxId,
                    row.topic,
                    row.attempts,
                    next.toMillis(),
                    t.message,
                )
                outboxRepository.markFailed(
                    outboxId = row.outboxId,
                    error = t.message ?: t::class.java.name,
                    nextAttemptAt = Instant.now().plus(next),
                )
            }
        }
    }

    private fun computeNextAttempt(attempts: Int): Duration {
        // Exponential backoff with caps.
        val baseMillis = 1_000L
        val exp = attempts.coerceIn(0, 10)
        val delay = baseMillis * (1L shl exp)
        return Duration.ofMillis(delay.coerceAtMost(15 * 60 * 1000L))
    }
}
