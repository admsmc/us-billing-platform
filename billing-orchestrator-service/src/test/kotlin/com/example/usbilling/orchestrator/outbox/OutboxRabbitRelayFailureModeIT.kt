package com.example.usbilling.orchestrator.outbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(RabbitOutboxRelayTestConfig::class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@TestPropertySource(
    properties = [
        "spring.task.scheduling.enabled=false",
        "orchestrator.outbox.rabbit.relay.enabled=true",
        "orchestrator.outbox.rabbit.relay.batch-size=10",
        "orchestrator.outbox.rabbit.relay.lock-owner=rabbit-outbox-relay-it",
        "orchestrator.outbox.rabbit.relay.lock-ttl-seconds=5",
    ],
)
@Transactional
class OutboxRabbitRelayFailureModeIT(
    private val rabbitOutboxRelay: RabbitOutboxRelay,
    private val outboxRepository: OutboxRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val rabbitTemplate: RabbitTemplate,
) {

    @BeforeEach
    fun setUp() {
        Mockito.reset(rabbitTemplate)
    }

    @Test
    fun `partial batch success marks one event sent and retries the failed one`() {
        val okEventId = "evt-rabbit-ok-${UUID.randomUUID()}"
        val failEventId = "evt-rabbit-fail-${UUID.randomUUID()}"

        outboxRepository.enqueue(
            topic = "exchange-1",
            eventKey = "routing-1",
            eventType = "TestRabbitEvent",
            eventId = okEventId,
            payloadJson = "{}",
            destinationType = OutboxDestinationType.RABBIT,
            now = Instant.EPOCH,
        )

        outboxRepository.enqueue(
            topic = "exchange-1",
            eventKey = "routing-1",
            eventType = "TestRabbitEvent",
            eventId = failEventId,
            payloadJson = "{}",
            destinationType = OutboxDestinationType.RABBIT,
            now = Instant.EPOCH,
        )

        // First send ok, second send throws.
        Mockito.doNothing()
            .doThrow(RuntimeException("boom"))
            .`when`(rabbitTemplate)
            .send(anyString(), anyString(), any(Message::class.java))

        rabbitOutboxRelay.tick()

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
        val eventId = "evt-rabbit-down-${UUID.randomUUID()}"

        outboxRepository.enqueue(
            topic = "exchange-1",
            eventKey = "routing-1",
            eventType = "TestRabbitEvent",
            eventId = eventId,
            payloadJson = "{}",
            destinationType = OutboxDestinationType.RABBIT,
            now = Instant.EPOCH,
        )

        Mockito.doThrow(RuntimeException("broker down"))
            .`when`(rabbitTemplate)
            .send(anyString(), anyString(), any(Message::class.java))

        rabbitOutboxRelay.tick()

        val row = outboxRow(eventId)
        assertEquals("PENDING", row["status"])
        assertEquals(1, (row["attempts"] as Number).toInt())
        assertNotNull(row["next_attempt_at"])
        assertNotNull(row["last_error"])
        assertEquals(null, row["locked_by"])
        assertEquals(null, row["locked_at"])
    }

    private fun outboxRow(eventId: String): Map<String, Any?> = jdbcTemplate.queryForMap(
        """
            SELECT status, attempts, next_attempt_at, last_error, locked_by, locked_at, published_at
            FROM outbox_event
            WHERE event_id = ? AND destination_type = 'RABBIT'
        """.trimIndent(),
        eventId,
    )
}
