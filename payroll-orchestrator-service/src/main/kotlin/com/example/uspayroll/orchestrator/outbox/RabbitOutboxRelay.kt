package com.example.uspayroll.orchestrator.outbox

import org.slf4j.LoggerFactory
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

@ConfigurationProperties(prefix = "orchestrator.outbox.rabbit.relay")
data class RabbitOutboxRelayProperties(
    var enabled: Boolean = false,
    var batchSize: Int = 200,
    var lockOwner: String = "rabbit-outbox-relay",
    var lockTtlSeconds: Long = 60,
    /** Fixed delay between relay runs. */
    var fixedDelayMillis: Long = 250L,
)

@Configuration
@EnableConfigurationProperties(RabbitOutboxRelayProperties::class)
class RabbitOutboxRelayConfig

@Component
@ConditionalOnProperty(prefix = "orchestrator.outbox.rabbit.relay", name = ["enabled"], havingValue = "true")
class RabbitOutboxRelay(
    private val props: RabbitOutboxRelayProperties,
    private val outboxRepository: OutboxRepository,
    private val rabbitTemplate: RabbitTemplate,
) {

    private val logger = LoggerFactory.getLogger(RabbitOutboxRelay::class.java)

    init {
        logger.info(
            "outbox.rabbit.relay.enabled batch_size={} lock_owner={} fixed_delay_millis={}",
            props.batchSize,
            props.lockOwner,
            props.fixedDelayMillis,
        )
    }

    @Scheduled(fixedDelayString = "\${orchestrator.outbox.rabbit.relay.fixed-delay-millis:250}")
    fun tick() {
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val lockTtl = Duration.ofSeconds(props.lockTtlSeconds.coerceAtLeast(5L))
        val claimed = outboxRepository.claimBatch(
            destinationType = OutboxDestinationType.RABBIT,
            limit = props.batchSize,
            lockOwner = props.lockOwner,
            lockTtl = lockTtl,
            now = now,
        )

        if (claimed.isEmpty()) return

        val sent: MutableList<OutboxEventRow> = mutableListOf()
        val failures: MutableList<OutboxFailureUpdate> = mutableListOf()

        claimed.forEach { row ->
            @Suppress("TooGenericExceptionCaught")
            try {
                // For Rabbit destinations:
                // - row.topic = exchange
                // - row.eventKey = routing key
                val msgProps = MessageProperties().apply {
                    contentType = MessageProperties.CONTENT_TYPE_JSON
                    contentEncoding = StandardCharsets.UTF_8.name()
                    if (!row.eventId.isNullOrBlank()) {
                        setHeader("X-Event-Id", row.eventId)
                    }
                    setHeader("X-Event-Type", row.eventType)
                    if (!row.aggregateId.isNullOrBlank()) {
                        setHeader("X-Aggregate-Id", row.aggregateId)
                    }
                }

                val msg = Message(row.payloadJson.toByteArray(StandardCharsets.UTF_8), msgProps)
                rabbitTemplate.send(row.topic, row.eventKey, msg)

                sent.add(row)
            } catch (t: Exception) {
                val next = computeNextAttempt(row.attempts)
                logger.warn(
                    "outbox.rabbit.publish.failed outbox_id={} exchange={} routing_key={} attempts={} next_delay_ms={} err={}",
                    row.outboxId,
                    row.topic,
                    row.eventKey,
                    row.attempts,
                    next.toMillis(),
                    t.message,
                )

                failures.add(
                    OutboxFailureUpdate(
                        outboxId = row.outboxId,
                        lockedAt = row.lockedAt,
                        error = t.message ?: t::class.java.name,
                        nextAttemptAt = now.plus(next),
                    ),
                )
            }
        }

        // Reduce commit/WAL overhead: bulk-update outbox rows after publish.
        sent
            .groupBy { it.lockedAt }
            .forEach { (lockedAt, rows) ->
                outboxRepository.markSentBatch(
                    destinationType = OutboxDestinationType.RABBIT,
                    outboxIds = rows.map { it.outboxId },
                    lockOwner = props.lockOwner,
                    lockedAt = lockedAt,
                    now = now,
                )
            }

        outboxRepository.markFailedBatch(
            destinationType = OutboxDestinationType.RABBIT,
            failures = failures,
            lockOwner = props.lockOwner,
        )
    }

    private fun computeNextAttempt(attempts: Int): Duration {
        // Exponential backoff with caps.
        val baseMillis = 250L
        val exp = attempts.coerceIn(0, 10)
        val delay = baseMillis * (1L shl exp)
        return Duration.ofMillis(delay.coerceAtMost(15 * 60 * 1000L))
    }
}
