package com.example.uspayroll.reporting.persistence

import com.example.uspayroll.messaging.events.reporting.PaycheckLedgerEvent
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant

@Repository
class PaycheckFinalizedRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    data class PaycheckFinalizedProjectionEvent(
        val eventId: String,
        val occurredAt: Instant,
        val employerId: String,
        val payRunId: String,
        val paycheckId: String,
        val employeeId: String,
    )

    /**
     * Upsert strategy:
     * - primary key: (employer_id, paycheck_id)
     * - keep latest occurred_at/event_id on replay/out-of-order
     */
    fun upsertFromEvent(event: PaycheckFinalizedProjectionEvent) {
        val ts = Timestamp.from(event.occurredAt)

        jdbcTemplate.update(
            """
            INSERT INTO paycheck_finalized (
              employer_id, paycheck_id, employee_id, pay_run_id,
              event_id, occurred_at,
              created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            event.employerId,
            event.paycheckId,
            event.employeeId,
            event.payRunId,
            event.eventId,
            ts,
        )

        jdbcTemplate.update(
            """
            UPDATE paycheck_finalized
            SET employee_id = ?,
                pay_run_id = ?,
                event_id = ?,
                occurred_at = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND paycheck_id = ?
              AND occurred_at <= ?
            """.trimIndent(),
            event.employeeId,
            event.payRunId,
            event.eventId,
            ts,
            event.employerId,
            event.paycheckId,
            ts,
        )
    }

    /**
     * Best-effort enrichment when the paycheck ledger event arrives later.
     *
     * This avoids requiring PaycheckFinalizedEvent to include check date.
     */
    fun enrichFromLedgerEvent(event: PaycheckLedgerEvent) {
        jdbcTemplate.update(
            """
            UPDATE paycheck_finalized
            SET pay_period_id = ?,
                pay_run_type = ?,
                run_sequence = ?,
                check_date = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND paycheck_id = ?
            """.trimIndent(),
            event.payPeriodId,
            event.payRunType,
            event.runSequence,
            Date.valueOf(event.checkDateIso),
            event.employerId,
            event.paycheckId,
        )
    }
}
