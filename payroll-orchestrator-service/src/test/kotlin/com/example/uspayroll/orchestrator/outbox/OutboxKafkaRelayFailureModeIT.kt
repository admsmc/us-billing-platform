package com.example.uspayroll.orchestrator.outbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.messaging.Message
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(KafkaOutboxRelayTestConfig::class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@TestPropertySource(
    properties = [
        "spring.task.scheduling.enabled=false",
        "orchestrator.outbox.relay.enabled=true",
        "orchestrator.outbox.relay.batch-size=10",
        "orchestrator.outbox.relay.lock-owner=outbox-relay-it",
        "orchestrator.outbox.relay.lock-ttl-seconds=5",
    ],
)
@Transactional
class OutboxKafkaRelayFailureModeIT(
    private val outboxRelay: OutboxRelay,
    private val outboxRepository: OutboxRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val kafkaTemplate: KafkaTemplate<String, String>,
) {

    @BeforeEach
    fun setUp() {
        Mockito.reset(kafkaTemplate)
    }

    @Test
    fun `partial batch success marks one event sent and retries the failed one`() {
        val okEventId = "evt-kafka-ok-${UUID.randomUUID()}"
        val failEventId = "evt-kafka-fail-${UUID.randomUUID()}"

        outboxRepository.enqueue(
            topic = "topic-1",
            eventKey = "k1",
            eventType = "TestEvent",
            eventId = okEventId,
            payloadJson = "{}",
            destinationType = OutboxDestinationType.KAFKA,
            now = Instant.EPOCH,
        )

        outboxRepository.enqueue(
            topic = "topic-1",
            eventKey = "k1",
            eventType = "TestEvent",
            eventId = failEventId,
            payloadJson = "{}",
            destinationType = OutboxDestinationType.KAFKA,
            now = Instant.EPOCH,
        )

        Mockito.`when`(kafkaTemplate.send(any(Message::class.java)))
            .thenReturn(okSendFuture())
            .thenReturn(CompletableFuture.failedFuture<SendResult<String, String>>(RuntimeException("boom")))

        outboxRelay.tick()

        val ok = outboxRow(okEventId)
        assertEquals("SENT", ok["status"])
        assertEquals(0, (ok["attempts"] as Number).toInt())
        assertNotNull(ok["published_at"])
        assertEquals(null, ok["locked_by"])
        assertEquals(null, ok["locked_at"])

        val fail = outboxRow(failEventId)
        assertEquals("PENDING", fail["status"])
        assertEquals(1, (fail["attempts"] as Number).toInt())
        assertNotNull(fail["next_attempt_at"])
        assertNotNull(fail["last_error"])
        assertEquals(null, fail["locked_by"])
        assertEquals(null, fail["locked_at"])
    }

    @Test
    fun `broker down transitions event back to pending with retry metadata`() {
        val eventId = "evt-kafka-down-${UUID.randomUUID()}"

        outboxRepository.enqueue(
            topic = "topic-1",
            eventKey = "k1",
            eventType = "TestEvent",
            eventId = eventId,
            payloadJson = "{}",
            destinationType = OutboxDestinationType.KAFKA,
            now = Instant.EPOCH,
        )

        Mockito.`when`(kafkaTemplate.send(any(Message::class.java)))
            .thenReturn(CompletableFuture.failedFuture<SendResult<String, String>>(RuntimeException("broker down")))

        outboxRelay.tick()

        val row = outboxRow(eventId)
        assertEquals("PENDING", row["status"])
        assertEquals(1, (row["attempts"] as Number).toInt())
        assertNotNull(row["next_attempt_at"])
        assertNotNull(row["last_error"])
        assertEquals(null, row["locked_by"])
        assertEquals(null, row["locked_at"])
    }

    private fun outboxRow(eventId: String): Map<String, Any?> {
        return jdbcTemplate.queryForMap(
            """
            SELECT status, attempts, next_attempt_at, last_error, locked_by, locked_at, published_at
            FROM outbox_event
            WHERE event_id = ? AND destination_type = 'KAFKA'
            """.trimIndent(),
            eventId,
        )
    }

    private fun okSendFuture(): CompletableFuture<SendResult<String, String>> {
        @Suppress("UNCHECKED_CAST")
        val sendResult = Mockito.mock(SendResult::class.java) as SendResult<String, String>
        return CompletableFuture.completedFuture(sendResult)
    }
}
