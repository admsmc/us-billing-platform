package com.example.uspayroll.payments.outbox

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
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
    /** Lease token for this claim; callers must echo it back to markSent/markFailed. */
    val lockedAt: Instant,
)

@Repository
class OutboxRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val isPostgres: Boolean by lazy {
        val ds = jdbcTemplate.dataSource ?: return@lazy false
        ds.connection.use { conn ->
            conn.metaData.databaseProductName.lowercase().contains("postgres")
        }
    }

    fun enqueue(topic: String, eventKey: String, eventType: String, eventId: String? = null, aggregateId: String? = null, payloadJson: String, now: Instant = Instant.now()): String {
        val outboxId = "outbox-${UUID.randomUUID()}"
        val ts = Timestamp.from(now)

        if (isPostgres) {
            val inserted = jdbcTemplate.update(
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
                ON CONFLICT DO NOTHING
                """.trimIndent(),
                outboxId,
                OutboxStatus.PENDING.name,
                eventId,
                topic,
                eventKey,
                eventType,
                aggregateId,
                payloadJson,
                ts,
                ts,
            )

            if (inserted == 1) return outboxId

            if (!eventId.isNullOrBlank()) {
                val existing = jdbcTemplate.query(
                    """
                    SELECT outbox_id
                    FROM outbox_event
                    WHERE event_id = ?
                    LIMIT 1
                    """.trimIndent(),
                    { rs, _ -> rs.getString("outbox_id") },
                    eventId,
                ).firstOrNull()

                if (!existing.isNullOrBlank()) return existing
            }

            error("outbox enqueue conflicted but existing row not found eventId=$eventId")
        }

        // H2 tests: fall back to exception-based idempotency.
        return try {
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
                ts,
                ts,
            )
            outboxId
        } catch (_: DataIntegrityViolationException) {
            if (!eventId.isNullOrBlank()) {
                val existing = jdbcTemplate.query(
                    """
                    SELECT outbox_id
                    FROM outbox_event
                    WHERE event_id = ?
                    LIMIT 1
                    """.trimIndent(),
                    { rs, _ -> rs.getString("outbox_id") },
                    eventId,
                ).firstOrNull()

                if (!existing.isNullOrBlank()) return existing
            }
            throw IllegalStateException("outbox enqueue conflicted but existing row not found eventId=$eventId")
        }
    }

    /**
     * Claims a batch of PENDING events, transitions them to SENDING, and returns the claimed rows.
     */
    @Transactional
    fun claimBatch(limit: Int, lockOwner: String, lockTtl: Duration, now: Instant = Instant.now()): List<OutboxEventRow> {
        val effectiveLimit = limit.coerceIn(1, 500)
        val leaseAt = now.truncatedTo(ChronoUnit.MILLIS)
        val nowTs = Timestamp.from(leaseAt)
        val cutoffTs = Timestamp.from(leaseAt.minus(lockTtl))

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
                    lockedAt = leaseAt,
                )
            },
            OutboxStatus.PENDING.name,
            nowTs,
            cutoffTs,
            effectiveLimit,
        )

        if (candidates.isEmpty()) return emptyList()

        val ids = candidates.map { it.outboxId }

        val updatedCounts = jdbcTemplate.batchUpdate(
            """
            UPDATE outbox_event
            SET status = ?, locked_by = ?, locked_at = ?
            WHERE outbox_id = ?
              AND status = ?
              AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
              AND (locked_at IS NULL OR locked_at < ?)
            """.trimIndent(),
            ids.map { outboxId ->
                arrayOf(
                    OutboxStatus.SENDING.name,
                    lockOwner,
                    nowTs,
                    outboxId,
                    OutboxStatus.PENDING.name,
                    nowTs,
                    cutoffTs,
                )
            },
        )

        val claimedIds = ids
            .zip(updatedCounts.asList())
            .filter { (_, updated) -> updated == 1 }
            .map { (id, _) -> id }
            .toSet()

        return candidates.filter { it.outboxId in claimedIds }
    }

    fun markSent(outboxId: String, lockOwner: String, lockedAt: Instant, now: Instant = Instant.now()): Int {
        // Only the current lock owner should be able to mark a row SENT.
        return jdbcTemplate.update(
            """
            UPDATE outbox_event
            SET status = ?,
                published_at = ?,
                locked_by = NULL,
                locked_at = NULL,
                last_error = NULL
            WHERE outbox_id = ?
              AND status = ?
              AND locked_by = ?
              AND locked_at = ?
            """.trimIndent(),
            OutboxStatus.SENT.name,
            Timestamp.from(now),
            outboxId,
            OutboxStatus.SENDING.name,
            lockOwner,
            Timestamp.from(lockedAt),
        )
    }

    fun markFailed(outboxId: String, lockOwner: String, lockedAt: Instant, error: String, nextAttemptAt: Instant, now: Instant = Instant.now()): Int {
        val truncated = if (error.length <= 2000) error else error.take(2000)

        // Only the current lock owner should be able to release the lock and requeue.
        return jdbcTemplate.update(
            """
            UPDATE outbox_event
            SET status = ?,
                attempts = attempts + 1,
                next_attempt_at = ?,
                last_error = ?,
                locked_by = NULL,
                locked_at = NULL
            WHERE outbox_id = ?
              AND status = ?
              AND locked_by = ?
              AND locked_at = ?
            """.trimIndent(),
            OutboxStatus.PENDING.name,
            Timestamp.from(nextAttemptAt),
            truncated,
            outboxId,
            OutboxStatus.SENDING.name,
            lockOwner,
            Timestamp.from(lockedAt),
        )
    }
}
