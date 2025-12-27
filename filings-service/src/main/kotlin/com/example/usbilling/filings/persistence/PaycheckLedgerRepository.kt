package com.example.usbilling.filings.persistence

import com.example.usbilling.messaging.events.reporting.PaycheckLedgerEvent
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

@Repository
class PaycheckLedgerRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {

    /**
     * Upsert strategy:
     * - primary key: (employer_id, paycheck_id)
     * - keep the latest occurred_at/event_id payload when reprocessing
     */
    fun upsertFromEvent(event: PaycheckLedgerEvent) {
        val auditJson = event.audit?.let { objectMapper.writeValueAsString(it) }
        val payloadJson = objectMapper.writeValueAsString(event)

        // 1) Insert if absent.
        jdbcTemplate.update(
            """
            INSERT INTO paycheck_ledger_entry (
              employer_id, paycheck_id, employee_id,
              pay_run_id, pay_run_type, run_sequence, pay_period_id,
              check_date, action,
              currency, gross_cents, net_cents,
              event_id, occurred_at,
              audit_json, payload_json,
              created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            event.employerId,
            event.paycheckId,
            event.employeeId,
            event.payRunId,
            event.payRunType,
            event.runSequence,
            event.payPeriodId,
            Date.valueOf(event.checkDateIso),
            event.action.name,
            event.currency,
            event.grossCents,
            event.netCents,
            event.eventId,
            Timestamp.from(event.occurredAt),
            auditJson,
            payloadJson,
        )

        // 2) Update if our event is newer (best-effort idempotency for replay/out-of-order delivery).
        jdbcTemplate.update(
            """
            UPDATE paycheck_ledger_entry
            SET employee_id = ?,
                pay_run_id = ?,
                pay_run_type = ?,
                run_sequence = ?,
                pay_period_id = ?,
                check_date = ?,
                action = ?,
                currency = ?,
                gross_cents = ?,
                net_cents = ?,
                event_id = ?,
                occurred_at = ?,
                audit_json = ?,
                payload_json = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND paycheck_id = ?
              AND occurred_at <= ?
            """.trimIndent(),
            event.employeeId,
            event.payRunId,
            event.payRunType,
            event.runSequence,
            event.payPeriodId,
            Date.valueOf(event.checkDateIso),
            event.action.name,
            event.currency,
            event.grossCents,
            event.netCents,
            event.eventId,
            Timestamp.from(event.occurredAt),
            auditJson,
            payloadJson,
            event.employerId,
            event.paycheckId,
            Timestamp.from(event.occurredAt),
        )
    }

    fun listEventsByEmployerAndCheckDateRange(
        employerId: String,
        startInclusive: LocalDate,
        endInclusive: LocalDate,
    ): List<PaycheckLedgerEvent> = jdbcTemplate.query(
        """
            SELECT payload_json
            FROM paycheck_ledger_entry
            WHERE employer_id = ?
              AND check_date >= ?
              AND check_date <= ?
            ORDER BY check_date, employee_id, paycheck_id
        """.trimIndent(),
        { rs, _ -> rs.getString("payload_json") },
        employerId,
        Date.valueOf(startInclusive),
        Date.valueOf(endInclusive),
    ).map { objectMapper.readValue(it, PaycheckLedgerEvent::class.java) }

    data class PaycheckNetRow(
        val paycheckId: String,
        val employeeId: String,
        val netCents: Long,
        val checkDate: LocalDate,
    )

    fun listPaycheckNetByEmployerAndCheckDateRange(
        employerId: String,
        startInclusive: LocalDate,
        endInclusive: LocalDate,
    ): List<PaycheckNetRow> = jdbcTemplate.query(
        """
            SELECT paycheck_id, employee_id, net_cents, check_date
            FROM paycheck_ledger_entry
            WHERE employer_id = ?
              AND check_date >= ?
              AND check_date <= ?
            ORDER BY check_date, employee_id, paycheck_id
        """.trimIndent(),
        { rs, _ ->
            PaycheckNetRow(
                paycheckId = rs.getString("paycheck_id"),
                employeeId = rs.getString("employee_id"),
                netCents = rs.getLong("net_cents"),
                checkDate = rs.getDate("check_date").toLocalDate(),
            )
        },
        employerId,
        Date.valueOf(startInclusive),
        Date.valueOf(endInclusive),
    )

    fun maxOccurredAt(employerId: String): Instant? {
        val ts = jdbcTemplate.query(
            """
            SELECT MAX(occurred_at) AS max_ts
            FROM paycheck_ledger_entry
            WHERE employer_id = ?
            """.trimIndent(),
            { rs, _ -> rs.getTimestamp("max_ts") },
            employerId,
        ).firstOrNull() ?: return null

        return ts?.toInstant()
    }
}
