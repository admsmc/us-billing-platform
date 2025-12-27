package com.example.usbilling.reporting.persistence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

@Repository
class PayRunSummaryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    data class PayRunFinalizedProjectionEvent(
        val eventId: String,
        val occurredAt: Instant,
        val employerId: String,
        val payRunId: String,
        val payPeriodId: String,
        val status: String,
        val total: Int,
        val succeeded: Int,
        val failed: Int,
    )

    /**
     * Upsert strategy:
     * - primary key: (employer_id, pay_run_id)
     * - keep latest occurred_at/event_id on replay/out-of-order
     */
    fun upsertFromEvent(event: PayRunFinalizedProjectionEvent) {
        val ts = Timestamp.from(event.occurredAt)

        jdbcTemplate.update(
            """
            INSERT INTO pay_run_summary (
              employer_id, pay_run_id, pay_period_id,
              status, total, succeeded, failed,
              event_id, occurred_at,
              created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            event.employerId,
            event.payRunId,
            event.payPeriodId,
            event.status,
            event.total,
            event.succeeded,
            event.failed,
            event.eventId,
            ts,
        )

        jdbcTemplate.update(
            """
            UPDATE pay_run_summary
            SET pay_period_id = ?,
                status = ?,
                total = ?,
                succeeded = ?,
                failed = ?,
                event_id = ?,
                occurred_at = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND pay_run_id = ?
              AND occurred_at <= ?
            """.trimIndent(),
            event.payPeriodId,
            event.status,
            event.total,
            event.succeeded,
            event.failed,
            event.eventId,
            ts,
            event.employerId,
            event.payRunId,
            ts,
        )
    }
}
