package com.example.uspayroll.messaging.inbox

import java.sql.SQLException
import javax.sql.DataSource

/**
 * Recommended inbox table schema for consumer-side idempotency.
 *
 * Each consumer service should create this table in its own database.
 */
object EventInboxSchema {
    val ddl: String = """
        CREATE TABLE event_inbox (
          consumer VARCHAR(128) NOT NULL,
          event_id VARCHAR(256) NOT NULL,
          received_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
          PRIMARY KEY (consumer, event_id)
        );
    """.trimIndent()
}

/**
 * Simple DB-backed de-duplication primitive.
 *
 * Usage: if [tryMarkProcessed] returns true, process the event; otherwise skip.
 */
class JdbcEventInbox(
    private val dataSource: DataSource,
    private val tableName: String = "event_inbox",
) {

    fun tryMarkProcessed(consumer: String, eventId: String): Boolean {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO $tableName (consumer, event_id) VALUES (?, ?)",
            ).use { ps ->
                ps.setString(1, consumer)
                ps.setString(2, eventId)
                return try {
                    ps.executeUpdate()
                    true
                } catch (e: SQLException) {
                    // SQLState class '23' indicates integrity constraint violation across many DBs.
                    val state = e.sqlState ?: ""
                    if (state.startsWith("23")) {
                        false
                    } else {
                        throw e
                    }
                }
            }
        }
    }

    /**
     * Best-effort rollback of a previously-marked event.
     *
     * This is primarily used to avoid "lost" processing when a consumer marks an event
     * as processed and then throws before completing business effects.
     */
    fun unmarkProcessed(consumer: String, eventId: String): Int {
        return dataSource.connection.use { conn ->
            conn.prepareStatement(
                "DELETE FROM $tableName WHERE consumer = ? AND event_id = ?",
            ).use { ps ->
                ps.setString(1, consumer)
                ps.setString(2, eventId)
                ps.executeUpdate()
            }
        }
    }

    /**
     * Runs [block] at most once per (consumer, eventId).
     *
     * If [block] throws, this will best-effort remove the inbox marker so that
     * the message can be retried.
     */
    @Suppress("TooGenericExceptionCaught")
    inline fun <T> runIfFirst(consumer: String, eventId: String, block: () -> T): T? {
        if (!tryMarkProcessed(consumer, eventId)) return null

        return try {
            block()
        } catch (t: Throwable) {
            try {
                unmarkProcessed(consumer, eventId)
            } catch (_: SQLException) {
                // If we cannot unmark, the original error still matters most.
            }
            throw t
        }
    }
}
