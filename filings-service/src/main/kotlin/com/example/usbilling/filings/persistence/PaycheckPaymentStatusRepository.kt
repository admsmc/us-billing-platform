package com.example.usbilling.filings.persistence

import com.example.usbilling.messaging.events.payments.PaycheckPaymentLifecycleStatus
import com.example.usbilling.messaging.events.payments.PaycheckPaymentStatusChangedEvent
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp

@Repository
class PaycheckPaymentStatusRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    fun upsertFromEvent(event: PaycheckPaymentStatusChangedEvent) {
        // 1) Insert if absent.
        jdbcTemplate.update(
            """
            INSERT INTO paycheck_payment_status (
              employer_id, paycheck_id,
              pay_run_id, payment_id,
              status,
              event_id, occurred_at,
              updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            event.employerId,
            event.paycheckId,
            event.payRunId,
            event.paymentId,
            event.status.name,
            event.eventId,
            Timestamp.from(event.occurredAt),
        )

        // 2) Update if newer.
        jdbcTemplate.update(
            """
            UPDATE paycheck_payment_status
            SET pay_run_id = ?,
                payment_id = ?,
                status = ?,
                event_id = ?,
                occurred_at = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND paycheck_id = ?
              AND occurred_at <= ?
            """.trimIndent(),
            event.payRunId,
            event.paymentId,
            event.status.name,
            event.eventId,
            Timestamp.from(event.occurredAt),
            event.employerId,
            event.paycheckId,
            Timestamp.from(event.occurredAt),
        )
    }

    data class StatusRow(
        val paycheckId: String,
        val status: PaycheckPaymentLifecycleStatus,
    )

    fun findStatusesByPaycheckIds(employerId: String, paycheckIds: List<String>): Map<String, PaycheckPaymentLifecycleStatus> {
        if (paycheckIds.isEmpty()) return emptyMap()

        val placeholders = paycheckIds.joinToString(",") { "?" }

        val rows = jdbcTemplate.query(
            """
            SELECT paycheck_id, status
            FROM paycheck_payment_status
            WHERE employer_id = ?
              AND paycheck_id IN ($placeholders)
            """.trimIndent(),
            { ps ->
                var i = 1
                ps.setString(i++, employerId)
                paycheckIds.forEach { id -> ps.setString(i++, id) }
            },
        ) { rs, _ ->
            rs.getString("paycheck_id") to PaycheckPaymentLifecycleStatus.valueOf(rs.getString("status"))
        }

        return rows.toMap()
    }
}
