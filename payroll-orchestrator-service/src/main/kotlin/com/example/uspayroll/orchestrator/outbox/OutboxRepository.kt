package com.example.uspayroll.orchestrator.outbox

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class OutboxStatus {
    PENDING,
    SENDING,
    SENT,
}

data class OutboxEventRow(
    val outboxId: String,
    val eventId: String?,
    val topic: String,
    val eventKey: String,
    val eventType: String,
    val aggregateId: String?,
    val payloadJson: String,
    val attempts: Int,
)

@Repository
class OutboxRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    fun enqueue(
        topic: String,
        eventKey: String,
        eventType: String,
        eventId: String? = null,
        aggregateId: String? = null,
        payloadJson: String,
        now: Instant = Instant.now(),
    ): String {
        val outboxId = "outbox-${UUID.randomUUID()}"
        jdbcTemplate.update(
            """
            INSERT INTO outbox_event (
              outbox_id, status,
              event_id,
              topic, event_key, event_type, aggregate_id,
              payload_json,
              attempts, next_attempt_at, last_error,
              locked_by, locked_at,
              created_at, published_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, NULL, NULL, NULL, ?, NULL)
            """.trimIndent(),
            outboxId,
            OutboxStatus.PENDING.name,
            eventId,
            topic,
            eventKey,
            eventType,
            aggregateId,
            payloadJson,
            Timestamp.from(now),
            Timestamp.from(now),
        )
        return outboxId
    }

    fun enqueueBatch(
        rows: List<PendingOutboxInsert>,
        now: Instant = Instant.now(),
    ): List<String> {
        if (rows.isEmpty()) return emptyList()
        val ts = Timestamp.from(now)

        val ids = rows.map { "outbox-${UUID.randomUUID()}" }

        jdbcTemplate.batchUpdate(
            """
            INSERT INTO outbox_event (
              outbox_id, status,
              event_id,
              topic, event_key, event_type, aggregate_id,
              payload_json,
              attempts, next_attempt_at, last_error,
              locked_by, locked_at,
              created_at, published_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, NULL, NULL, NULL, ?, NULL)
            """.trimIndent(),
            ids.indices.map { i ->
                val row = rows[i]
                arrayOf(
                    ids[i],
                    OutboxStatus.PENDING.name,
                    row.eventId,
                    row.topic,
                    row.eventKey,
                    row.eventType,
                    row.aggregateId,
                    row.payloadJson,
                    ts,
                    ts,
                )
            },
        )

        return ids
    }

    data class PendingOutboxInsert(
        val topic: String,
        val eventKey: String,
        val eventType: String,
        val eventId: String? = null,
        val aggregateId: String? = null,
        val payloadJson: String,
    )

    /**
     * Claims a batch of PENDING events, transitions them to SENDING, and returns the claimed rows.
     *
     * NOTE: Uses SELECT ... FOR UPDATE to prevent double-claim. This is portable across
     * many SQL engines and works with H2 for tests.
     */
    @Transactional
    fun claimBatch(
        limit: Int,
        lockOwner: String,
        lockTtl: Duration,
        now: Instant = Instant.now(),
    ): List<OutboxEventRow> {
        val effectiveLimit = limit.coerceIn(1, 500)
        val nowTs = Timestamp.from(now)
        val cutoffTs = Timestamp.from(now.minus(lockTtl))

        val candidates = jdbcTemplate.query(
            """
            SELECT outbox_id, event_id, topic, event_key, event_type, aggregate_id, payload_json, attempts
            FROM outbox_event
            WHERE status = ?
              AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
              AND (locked_at IS NULL OR locked_at < ?)
            ORDER BY created_at
            LIMIT ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                OutboxEventRow(
                    outboxId = rs.getString("outbox_id"),
                    eventId = rs.getString("event_id"),
                    topic = rs.getString("topic"),
                    eventKey = rs.getString("event_key"),
                    eventType = rs.getString("event_type"),
                    aggregateId = rs.getString("aggregate_id"),
                    payloadJson = rs.getString("payload_json"),
                    attempts = rs.getInt("attempts"),
                )
            },
            OutboxStatus.PENDING.name,
            nowTs,
            cutoffTs,
            effectiveLimit,
        )

        if (candidates.isEmpty()) return emptyList()

        val ids = candidates.map { it.outboxId }
        val placeholders = ids.joinToString(",") { "?" }
        val args: MutableList<Any> = mutableListOf(
            OutboxStatus.SENDING.name,
            lockOwner,
            nowTs,
        )
        args.addAll(ids)

        jdbcTemplate.update(
            """
            UPDATE outbox_event
            SET status = ?, locked_by = ?, locked_at = ?
            WHERE outbox_id IN ($placeholders)
            """.trimIndent(),
            *args.toTypedArray(),
        )

        return candidates
    }

    fun markSent(outboxId: String, now: Instant = Instant.now()) {
        jdbcTemplate.update(
            """
            UPDATE outbox_event
            SET status = ?,
                published_at = ?,
                locked_by = NULL,
                locked_at = NULL,
                last_error = NULL
            WHERE outbox_id = ?
            """.trimIndent(),
            OutboxStatus.SENT.name,
            Timestamp.from(now),
            outboxId,
        )
    }

    fun markFailed(
        outboxId: String,
        error: String,
        nextAttemptAt: Instant,
        now: Instant = Instant.now(),
    ) {
        val truncated = if (error.length <= 2000) error else error.take(2000)

        jdbcTemplate.update(
            """
            UPDATE outbox_event
            SET status = ?,
                attempts = attempts + 1,
                next_attempt_at = ?,
                last_error = ?,
                locked_by = NULL,
                locked_at = NULL
            WHERE outbox_id = ?
            """.trimIndent(),
            OutboxStatus.PENDING.name,
            Timestamp.from(nextAttemptAt),
            truncated,
            outboxId,
        )
    }
}
