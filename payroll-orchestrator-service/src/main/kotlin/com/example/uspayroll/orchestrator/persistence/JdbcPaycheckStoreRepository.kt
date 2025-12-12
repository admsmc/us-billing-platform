package com.example.uspayroll.orchestrator.persistence

import com.example.uspayroll.payroll.model.PaycheckResult
import com.example.uspayroll.shared.EmployerId
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

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
        val json = objectMapper.writeValueAsString(payload)

        // "Immutable" insert: if the paycheck already exists (retry), we do nothing.
        try {
            jdbcTemplate.update(
                """
                INSERT INTO paycheck (
                  employer_id, paycheck_id,
                  pay_run_id, employee_id, pay_period_id, run_type, run_sequence, check_date,
                  currency, gross_cents, net_cents,
                  status, version,
                  payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                employerId.value,
                paycheckId,
                payRunId,
                employeeId,
                payPeriodId,
                runType,
                runSequence,
                checkDateIso,
                payload.gross.currency,
                grossCents,
                netCents,
                "FINAL",
                version,
                json,
            )
        } catch (e: org.springframework.dao.DataIntegrityViolationException) {
            // Primary key or uq_paycheck_slot constraint hit: treat as idempotent no-op.
        }
    }

    override fun findPaycheck(
        employerId: EmployerId,
        paycheckId: String,
    ): PaycheckResult? {
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
}
