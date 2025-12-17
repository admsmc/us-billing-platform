package com.example.uspayroll.orchestrator.outbox

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

enum class OutboxDestinationType {
    KAFKA,
    RABBIT,
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

data class OutboxFailureUpdate(
    val outboxId: String,
    val lockedAt: Instant,
    val error: String,
    val nextAttemptAt: Instant,
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
        destinationType: OutboxDestinationType = OutboxDestinationType.KAFKA,
        now: Instant = Instant.now(),
    ): String {
        val outboxId = "outbox-${UUID.randomUUID()}"
        val inserted = jdbcTemplate.update(
            """
            INSERT INTO outbox_event (
              outbox_id, status,
              event_id,
              destination_type,
              topic, event_key, event_type, aggregate_id,
              payload_json,
              attempts, next_attempt_at, last_error,
              locked_by, locked_at,
              created_at, published_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, NULL, NULL, NULL, ?, NULL)
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            outboxId,
            OutboxStatus.PENDING.name,
            eventId,
            destinationType.name,
            topic,
            eventKey,
            eventType,
            aggregateId,
            payloadJson,
            Timestamp.from(now),
            Timestamp.from(now),
        )

        if (inserted == 1) return outboxId

        // If we attempted to insert a deterministic event, return the existing outbox row.
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

        // Otherwise, treat as unexpected.
        error("outbox enqueue conflicted but existing row not found eventId=$eventId")
    }

    fun enqueueBatch(rows: List<PendingOutboxInsert>, now: Instant = Instant.now()): List<String> {
        if (rows.isEmpty()) return emptyList()
        val ts = Timestamp.from(now)

        val ids = rows.map { "outbox-${UUID.randomUUID()}" }

        jdbcTemplate.batchUpdate(
            """
            INSERT INTO outbox_event (
              outbox_id, status,
              event_id,
              destination_type,
              topic, event_key, event_type, aggregate_id,
              payload_json,
              attempts, next_attempt_at, last_error,
              locked_by, locked_at,
              created_at, published_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, NULL, NULL, NULL, ?, NULL)
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            ids.indices.map { i ->
                val row = rows[i]
                arrayOf(
                    ids[i],
                    OutboxStatus.PENDING.name,
                    row.eventId,
                    row.destinationType.name,
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
        val destinationType: OutboxDestinationType = OutboxDestinationType.KAFKA,
    )

    /**
     * Claims a batch of PENDING events, transitions them to SENDING, and returns the claimed rows.
     *
     * NOTE: Uses SELECT ... FOR UPDATE to prevent double-claim. This is portable across
     * many SQL engines and works with H2 for tests.
     */
    @Transactional
    fun claimBatch(destinationType: OutboxDestinationType, limit: Int, lockOwner: String, lockTtl: Duration, now: Instant = Instant.now()): List<OutboxEventRow> {
        val effectiveLimit = limit.coerceIn(1, 500)
        val leaseAt = now.truncatedTo(ChronoUnit.MILLIS)
        val nowTs = Timestamp.from(leaseAt)
        val cutoffTs = Timestamp.from(leaseAt.minus(lockTtl))

        val candidates = jdbcTemplate.query(
            """
            SELECT outbox_id, event_id, topic, event_key, event_type, aggregate_id, payload_json, attempts
            FROM outbox_event
            WHERE destination_type = ?
              AND status = ?
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
            destinationType.name,
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
            WHERE destination_type = ?
              AND outbox_id = ?
              AND status = ?
              AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
              AND (locked_at IS NULL OR locked_at < ?)
            """.trimIndent(),
            ids.map { outboxId ->
                arrayOf(
                    OutboxStatus.SENDING.name,
                    lockOwner,
                    nowTs,
                    destinationType.name,
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

    fun markSent(destinationType: OutboxDestinationType, outboxId: String, lockOwner: String, lockedAt: Instant, now: Instant = Instant.now()): Int {
        return jdbcTemplate.update(
            """
            UPDATE outbox_event
            SET status = ?,
                published_at = ?,
                locked_by = NULL,
                locked_at = NULL,
                last_error = NULL
            WHERE destination_type = ?
              AND outbox_id = ?
              AND status = ?
              AND locked_by = ?
              AND locked_at = ?
            """.trimIndent(),
            OutboxStatus.SENT.name,
            Timestamp.from(now),
            destinationType.name,
            outboxId,
            OutboxStatus.SENDING.name,
            lockOwner,
            Timestamp.from(lockedAt),
        )
    }

    /**
     * Marks a batch of outbox rows as SENT in a single transaction.
     *
     * This dramatically reduces commit/WAL sync overhead vs per-row updates.
     */
    @Transactional
    fun markSentBatch(
        destinationType: OutboxDestinationType,
        outboxIds: List<String>,
        lockOwner: String,
        lockedAt: Instant,
        now: Instant = Instant.now(),
    ): Int {
        val distinct = outboxIds.distinct()
        if (distinct.isEmpty()) return 0

        val placeholders = distinct.joinToString(",") { "?" }
        val args: MutableList<Any> = mutableListOf(
            OutboxStatus.SENT.name,
            Timestamp.from(now),
            destinationType.name,
            OutboxStatus.SENDING.name,
            lockOwner,
            Timestamp.from(lockedAt),
        )
        args.addAll(distinct)

        return jdbcTemplate.update(
            """
            UPDATE outbox_event
            SET status = ?,
                published_at = ?,
                locked_by = NULL,
                locked_at = NULL,
                last_error = NULL
            WHERE destination_type = ?
              AND status = ?
              AND locked_by = ?
              AND locked_at = ?
              AND outbox_id IN ($placeholders)
            """.trimIndent(),
            *args.toTypedArray(),
        )
    }

    fun markFailed(destinationType: OutboxDestinationType, outboxId: String, lockOwner: String, lockedAt: Instant, error: String, nextAttemptAt: Instant, now: Instant = Instant.now()): Int {
        val truncated = if (error.length <= 2000) error else error.take(2000)

        return jdbcTemplate.update(
            """
            UPDATE outbox_event
            SET status = ?,
                attempts = attempts + 1,
                next_attempt_at = ?,
                last_error = ?,
                locked_by = NULL,
                locked_at = NULL
            WHERE destination_type = ?
              AND outbox_id = ?
              AND status = ?
              AND locked_by = ?
              AND locked_at = ?
            """.trimIndent(),
            OutboxStatus.PENDING.name,
            Timestamp.from(nextAttemptAt),
            truncated,
            destinationType.name,
            outboxId,
            OutboxStatus.SENDING.name,
            lockOwner,
            Timestamp.from(lockedAt),
        )
    }

    /**
     * Marks a batch of outbox rows as failed (status=PENDING, attempts++, next_attempt_at set)
     * in a single transaction.
     */
    @Transactional
    fun markFailedBatch(
        destinationType: OutboxDestinationType,
        failures: List<OutboxFailureUpdate>,
        lockOwner: String,
    ): Int {
        if (failures.isEmpty()) return 0

        val updated = jdbcTemplate.batchUpdate(
            """
            UPDATE outbox_event
            SET status = ?,
                attempts = attempts + 1,
                next_attempt_at = ?,
                last_error = ?,
                locked_by = NULL,
                locked_at = NULL
            WHERE destination_type = ?
              AND outbox_id = ?
              AND status = ?
              AND locked_by = ?
              AND locked_at = ?
            """.trimIndent(),
            failures.map { f ->
                val truncated = if (f.error.length <= 2000) f.error else f.error.take(2000)
                arrayOf(
                    OutboxStatus.PENDING.name,
                    Timestamp.from(f.nextAttemptAt),
                    truncated,
                    destinationType.name,
                    f.outboxId,
                    OutboxStatus.SENDING.name,
                    lockOwner,
                    Timestamp.from(f.lockedAt),
                )
            },
        )

        return updated.sum()
    }
}
