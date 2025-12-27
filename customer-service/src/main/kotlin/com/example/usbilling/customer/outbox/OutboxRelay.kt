package com.example.usbilling.customer.outbox

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

@ConfigurationProperties(prefix = "customer.outbox.relay")
data class OutboxRelayProperties(
    var enabled: Boolean = false,
    var batchSize: Int = 100,
    var lockOwner: String = "customer-outbox-relay",
    var lockTtlSeconds: Long = 60,
    var fixedDelayMillis: Long = 1_000L,
)

@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxRelayProperties::class)
class OutboxRelayConfig

/**
 * OutboxRelay scheduled task for publishing events from outbox to Kafka.
 * 
 * This implements the transactional outbox pattern relay:
 * 1. Claims batch of pending events (with lease)
 * 2. Publishes each event to Kafka
 * 3. Marks as sent on success, or requeues with backoff on failure
 * 
 * To enable: set customer.outbox.relay.enabled=true in application.yml
 */
@Component
@ConditionalOnProperty(prefix = "customer.outbox.relay", name = ["enabled"], havingValue = "true")
class OutboxRelay(
    private val props: OutboxRelayProperties,
    private val outboxRepository: OutboxRepository,
) {
    private val logger = LoggerFactory.getLogger(OutboxRelay::class.java)
    
    @Scheduled(fixedDelayString = "\${customer.outbox.relay.fixed-delay-millis:1000}")
    fun tick() {
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val lockTtl = Duration.ofSeconds(props.lockTtlSeconds.coerceAtLeast(5L))
        
        try {
            val claimed = outboxRepository.claimBatch(
                limit = props.batchSize,
                lockOwner = props.lockOwner,
                lockTtl = lockTtl,
                now = now,
            )
            
            if (claimed.isEmpty()) {
                return
            }
            
            logger.info("outbox.relay.claimed count={}", claimed.size)
            
            claimed.forEach { row ->
                try {
                    // TODO: Publish to Kafka when Kafka is configured
                    // For now, just simulate success and mark as sent
                    publishToKafka(row)
                    
                    outboxRepository.markSent(
                        outboxId = row.outboxId,
                        lockOwner = props.lockOwner,
                        lockedAt = row.lockedAt,
                        now = now,
                    )
                    
                    logger.debug(
                        "outbox.published outbox_id={} topic={} event_type={}",
                        row.outboxId,
                        row.topic,
                        row.eventType,
                    )
                } catch (t: Throwable) {
                    val nextDelay = computeNextAttempt(row.attempts)
                    
                    logger.warn(
                        "outbox.publish.failed outbox_id={} topic={} attempts={} next_delay_ms={} err={}",
                        row.outboxId,
                        row.topic,
                        row.attempts,
                        nextDelay.toMillis(),
                        t.message,
                    )
                    
                    outboxRepository.markFailed(
                        outboxId = row.outboxId,
                        lockOwner = props.lockOwner,
                        lockedAt = row.lockedAt,
                        error = t.message ?: t::class.java.name,
                        nextAttemptAt = now.plus(nextDelay),
                        now = now,
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("outbox.relay.error err={}", e.message, e)
        }
    }
    
    private fun publishToKafka(row: OutboxEventRow) {
        // TODO: Implement actual Kafka publishing when kafka is configured
        // For now, log the event that would be published
        
        logger.info(
            "outbox.kafka.publish topic={} key={} event_type={} event_id={} payload_size={}",
            row.topic,
            row.eventKey,
            row.eventType,
            row.eventId,
            row.payloadJson.length,
        )
        
        // Simulate Kafka publish (would be replaced with actual KafkaTemplate.send())
        // kafkaTemplate.send(row.topic, row.eventKey, row.payloadJson).get()
    }
    
    private fun computeNextAttempt(attempts: Int): Duration {
        // Exponential backoff: 1s, 2s, 4s, 8s, 16s, ... up to 15 minutes
        val baseMillis = 1_000L
        val exp = attempts.coerceIn(0, 10)
        val delay = baseMillis * (1L shl exp)
        return Duration.ofMillis(delay.coerceAtMost(15 * 60 * 1000L))
    }
}
