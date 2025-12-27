package com.example.usbilling.reporting.persistence

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

    data class LedgerEntrySummaryRow(
        val employerId: String,
        val paycheckId: String,
        val employeeId: String,
        val payRunId: String,
        val payRunType: String,
        val runSequence: Int,
        val payPeriodId: String,
        val checkDate: LocalDate,
        val action: String,
        val currency: String,
        val grossCents: Long,
        val netCents: Long,
        val eventId: String,
        val occurredAt: Instant,
    )

    data class LedgerEntryPayloadRow(
        val employerId: String,
        val paycheckId: String,
        val payloadJson: String,
    )

    fun listLedgerSummaryByEmployerAndCheckDateRange(employerId: String, startInclusive: LocalDate, endInclusive: LocalDate, limit: Int): List<LedgerEntrySummaryRow> = jdbcTemplate.query(
        """
            SELECT employer_id, paycheck_id, employee_id,
                   pay_run_id, pay_run_type, run_sequence, pay_period_id,
                   check_date, action,
                   currency, gross_cents, net_cents,
                   event_id, occurred_at
            FROM paycheck_ledger_entry
            WHERE employer_id = ?
              AND check_date >= ?
              AND check_date <= ?
            ORDER BY check_date, employee_id, paycheck_id
            LIMIT ?
        """.trimIndent(),
        { rs, _ ->
            LedgerEntrySummaryRow(
                employerId = rs.getString("employer_id"),
                paycheckId = rs.getString("paycheck_id"),
                employeeId = rs.getString("employee_id"),
                payRunId = rs.getString("pay_run_id"),
                payRunType = rs.getString("pay_run_type"),
                runSequence = rs.getInt("run_sequence"),
                payPeriodId = rs.getString("pay_period_id"),
                checkDate = rs.getDate("check_date").toLocalDate(),
                action = rs.getString("action"),
                currency = rs.getString("currency"),
                grossCents = rs.getLong("gross_cents"),
                netCents = rs.getLong("net_cents"),
                eventId = rs.getString("event_id"),
                occurredAt = rs.getTimestamp("occurred_at").toInstant(),
            )
        },
        employerId,
        Date.valueOf(startInclusive),
        Date.valueOf(endInclusive),
        limit,
    )

    fun listLedgerPayloadsByEmployerAndCheckDateRange(employerId: String, startInclusive: LocalDate, endInclusive: LocalDate, limit: Int): List<LedgerEntryPayloadRow> = jdbcTemplate.query(
        """
            SELECT employer_id, paycheck_id, payload_json
            FROM paycheck_ledger_entry
            WHERE employer_id = ?
              AND check_date >= ?
              AND check_date <= ?
            ORDER BY check_date, employee_id, paycheck_id
            LIMIT ?
        """.trimIndent(),
        { rs, _ ->
            LedgerEntryPayloadRow(
                employerId = rs.getString("employer_id"),
                paycheckId = rs.getString("paycheck_id"),
                payloadJson = rs.getString("payload_json"),
            )
        },
        employerId,
        Date.valueOf(startInclusive),
        Date.valueOf(endInclusive),
        limit,
    )

    fun findLedgerPayloadByEmployerAndBillId(employerId: String, paycheckId: String): LedgerEntryPayloadRow? = jdbcTemplate.query(
        """
            SELECT employer_id, paycheck_id, payload_json
            FROM paycheck_ledger_entry
            WHERE employer_id = ?
              AND paycheck_id = ?
        """.trimIndent(),
        { rs, _ ->
            LedgerEntryPayloadRow(
                employerId = rs.getString("employer_id"),
                paycheckId = rs.getString("paycheck_id"),
                payloadJson = rs.getString("payload_json"),
            )
        },
        employerId,
        paycheckId,
    ).firstOrNull()

    fun findLedgerSummaryByEmployerAndBillId(employerId: String, paycheckId: String): LedgerEntrySummaryRow? = jdbcTemplate.query(
        """
            SELECT employer_id, paycheck_id, employee_id,
                   pay_run_id, pay_run_type, run_sequence, pay_period_id,
                   check_date, action,
                   currency, gross_cents, net_cents,
                   event_id, occurred_at
            FROM paycheck_ledger_entry
            WHERE employer_id = ?
              AND paycheck_id = ?
        """.trimIndent(),
        { rs, _ ->
            LedgerEntrySummaryRow(
                employerId = rs.getString("employer_id"),
                paycheckId = rs.getString("paycheck_id"),
                employeeId = rs.getString("employee_id"),
                payRunId = rs.getString("pay_run_id"),
                payRunType = rs.getString("pay_run_type"),
                runSequence = rs.getInt("run_sequence"),
                payPeriodId = rs.getString("pay_period_id"),
                checkDate = rs.getDate("check_date").toLocalDate(),
                action = rs.getString("action"),
                currency = rs.getString("currency"),
                grossCents = rs.getLong("gross_cents"),
                netCents = rs.getLong("net_cents"),
                eventId = rs.getString("event_id"),
                occurredAt = rs.getTimestamp("occurred_at").toInstant(),
            )
        },
        employerId,
        paycheckId,
    ).firstOrNull()

    data class NetTotalsByEmployeeRow(
        val employerId: String,
        val payRunId: String,
        val employeeId: String,
        val paychecks: Int,
        val grossCentsTotal: Long,
        val netCentsTotal: Long,
    )

    fun listNetTotalsByEmployerAndPayRun(employerId: String, payRunId: String, limit: Int): List<NetTotalsByEmployeeRow> = jdbcTemplate.query(
        """
            SELECT employer_id,
                   pay_run_id,
                   employee_id,
                   COUNT(1) AS paychecks,
                   SUM(gross_cents) AS gross_total,
                   SUM(net_cents) AS net_total
            FROM paycheck_ledger_entry
            WHERE employer_id = ?
              AND pay_run_id = ?
            GROUP BY employer_id, pay_run_id, employee_id
            ORDER BY employee_id
            LIMIT ?
        """.trimIndent(),
        { rs, _ ->
            NetTotalsByEmployeeRow(
                employerId = rs.getString("employer_id"),
                payRunId = rs.getString("pay_run_id"),
                employeeId = rs.getString("employee_id"),
                paychecks = rs.getInt("paychecks"),
                grossCentsTotal = rs.getLong("gross_total"),
                netCentsTotal = rs.getLong("net_total"),
            )
        },
        employerId,
        payRunId,
        limit,
    )

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

    fun listEventsByEmployerAndCheckDateRange(employerId: String, startInclusive: LocalDate, endInclusive: LocalDate): List<PaycheckLedgerEvent> = jdbcTemplate.query(
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
