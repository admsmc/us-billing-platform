package com.example.usbilling.orchestrator.outbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.time.Instant

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@TestPropertySource(
    properties = [
        "spring.task.scheduling.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:orchestrator_outbox_repo_it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    ],
)
class OutboxRepositoryIT(
    private val outbox: OutboxRepository,
    private val jdbcTemplate: JdbcTemplate,
) {

    @Test
    fun `enqueue is idempotent for deterministic event id`() {
        val eventId = "evt-1"

        val id1 = outbox.enqueue(
            topic = "t-1",
            eventKey = "k-1",
            eventType = "MyEvent",
            eventId = eventId,
            aggregateId = "agg-1",
            payloadJson = "{}",
            destinationType = OutboxDestinationType.KAFKA,
        )

        val id2 = outbox.enqueue(
            topic = "t-1",
            eventKey = "k-1",
            eventType = "MyEvent",
            eventId = eventId,
            aggregateId = "agg-1",
            payloadJson = "{}",
            destinationType = OutboxDestinationType.KAFKA,
        )

        // On replay, the repository returns the existing outbox id.
        assertEquals(id1, id2)

        val rows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE event_id = ?",
            Long::class.java,
            eventId,
        ) ?: 0L
        assertEquals(1L, rows)
    }

    @Test
    fun `claimBatch enforces lease owner and supports retry backoff`() {
        val eventId = "evt-lease-1"
        val t0 = Instant.parse("2025-01-01T00:00:00Z")

        outbox.enqueue(
            topic = "t-lease",
            eventKey = "k-lease",
            eventType = "LeaseEvent",
            eventId = eventId,
            payloadJson = "{}",
            destinationType = OutboxDestinationType.KAFKA,
            now = t0,
        )

        val claimed = outbox.claimBatch(
            destinationType = OutboxDestinationType.KAFKA,
            limit = 10,
            lockOwner = "relay-1",
            lockTtl = Duration.ofSeconds(30),
            now = t0,
        )
        assertEquals(1, claimed.size)

        val row = claimed.first()
        assertNotNull(row.outboxId)
        assertEquals(eventId, row.eventId)

        // Wrong lock owner should not be able to transition state.
        assertEquals(
            0,
            outbox.markSent(
                destinationType = OutboxDestinationType.KAFKA,
                outboxId = row.outboxId,
                lockOwner = "relay-2",
                lockedAt = row.lockedAt,
                now = t0,
            ),
        )

        // Mark failed with a future retry time.
        val retryAt = t0.plusSeconds(60)
        assertEquals(
            1,
            outbox.markFailed(
                destinationType = OutboxDestinationType.KAFKA,
                outboxId = row.outboxId,
                lockOwner = "relay-1",
                lockedAt = row.lockedAt,
                error = "boom",
                nextAttemptAt = retryAt,
                now = t0,
            ),
        )

        // Not claimable before retry time.
        val beforeRetry = outbox.claimBatch(
            destinationType = OutboxDestinationType.KAFKA,
            limit = 10,
            lockOwner = "relay-1",
            lockTtl = Duration.ofSeconds(30),
            now = t0.plusSeconds(30),
        )
        assertTrue(beforeRetry.isEmpty())

        // Claimable after retry time.
        val afterRetry = outbox.claimBatch(
            destinationType = OutboxDestinationType.KAFKA,
            limit = 10,
            lockOwner = "relay-1",
            lockTtl = Duration.ofSeconds(30),
            now = retryAt.plusMillis(1),
        )
        assertEquals(1, afterRetry.size)

        val claimedAgain = afterRetry.first()
        assertEquals(row.outboxId, claimedAgain.outboxId)

        // Happy-path: mark sent with correct lock owner.
        assertEquals(
            1,
            outbox.markSent(
                destinationType = OutboxDestinationType.KAFKA,
                outboxId = claimedAgain.outboxId,
                lockOwner = "relay-1",
                lockedAt = claimedAgain.lockedAt,
                now = retryAt.plusSeconds(1),
            ),
        )
    }
}
