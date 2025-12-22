package com.example.uspayroll.orchestrator.persistence

import com.example.uspayroll.payroll.model.CalculationTrace
import com.example.uspayroll.payroll.model.PaycheckResult
import com.example.uspayroll.shared.EmployerId
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Date

@Repository
class JdbcPaycheckStoreRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) : PaycheckStoreRepository {

    override fun insertFinalPaycheckIfAbsent(
        employerId: EmployerId,
        paycheckId: String,
        payRunId: String,
        employeeId: String,
        payPeriodId: String,
        runType: String,
        runSequence: Int,
        checkDateIso: String,
        grossCents: Long,
        netCents: Long,
        version: Int,
        payload: PaycheckResult,
    ) {
        // Persist a trace-free payload. `TraceStep` is a sealed class without polymorphic
        // type info, and we don't need the trace for the current orchestrator read-path.
        val json = objectMapper.writeValueAsString(payload.copy(trace = CalculationTrace()))

        // "Immutable" insert: if the paycheck already exists (retry), we do nothing.
        // Use ON CONFLICT DO NOTHING to avoid Postgres transaction aborts.
        jdbcTemplate.update(
            """
                INSERT INTO paycheck (
                  employer_id, paycheck_id,
                  pay_run_id, employee_id, pay_period_id, run_type, run_sequence, check_date,
                  currency, gross_cents, net_cents,
                  status, version,
                  payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
            """.trimIndent(),
            employerId.value,
            paycheckId,
            payRunId,
            employeeId,
            payPeriodId,
            runType,
            runSequence,
            Date.valueOf(checkDateIso),
            payload.gross.currency.name,
            grossCents,
            netCents,
            "FINAL",
            version,
            json,
        )
    }

    override fun findPaycheck(employerId: EmployerId, paycheckId: String): PaycheckResult? {
        val json = jdbcTemplate.query(
            """
            SELECT payload_json
            FROM paycheck
            WHERE employer_id = ? AND paycheck_id = ?
            """.trimIndent(),
            { rs, _ -> rs.getString("payload_json") },
            employerId.value,
            paycheckId,
        ).firstOrNull() ?: return null

        return objectMapper.readValue(json, PaycheckResult::class.java)
    }

    override fun findCorrectionOfPaycheckId(employerId: EmployerId, paycheckId: String): String? = jdbcTemplate.query(
        """
            SELECT correction_of_paycheck_id
            FROM paycheck
            WHERE employer_id = ? AND paycheck_id = ?
        """.trimIndent(),
        { rs, _ -> rs.getString("correction_of_paycheck_id") },
        employerId.value,
        paycheckId,
    ).firstOrNull()

    override fun setCorrectionOfPaycheckIdIfNull(employerId: EmployerId, paycheckId: String, correctionOfPaycheckId: String): Boolean {
        val updated = jdbcTemplate.update(
            """
            UPDATE paycheck
            SET correction_of_paycheck_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND paycheck_id = ?
              AND correction_of_paycheck_id IS NULL
            """.trimIndent(),
            correctionOfPaycheckId,
            employerId.value,
            paycheckId,
        )
        return updated == 1
    }
}
