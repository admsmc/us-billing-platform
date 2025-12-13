package com.example.uspayroll.orchestrator.persistence

import com.example.uspayroll.payroll.model.audit.PaycheckAudit
import com.example.uspayroll.shared.EmployerId
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Date
import java.sql.Timestamp

@Repository
class JdbcPaycheckAuditStoreRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) : PaycheckAuditStoreRepository {

    override fun insertAuditIfAbsent(audit: PaycheckAudit) {
        val json = objectMapper.writeValueAsString(audit)

        // Idempotent insert.
        jdbcTemplate.update(
            """
                INSERT INTO paycheck_audit (
                  employer_id, paycheck_id,
                  pay_run_id, employee_id, pay_period_id, check_date,
                  schema_version, engine_version, computed_at,
                  audit_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
            """.trimIndent(),
            audit.employerId,
            audit.paycheckId,
            audit.payRunId,
            audit.employeeId,
            audit.payPeriodId,
            Date.valueOf(audit.checkDate),
            audit.schemaVersion,
            audit.engineVersion,
            Timestamp.from(audit.computedAt),
            json,
        )
    }

    override fun findAudit(employerId: EmployerId, paycheckId: String): PaycheckAudit? {
        val json = jdbcTemplate.query(
            """
            SELECT audit_json
            FROM paycheck_audit
            WHERE employer_id = ? AND paycheck_id = ?
            """.trimIndent(),
            { rs, _ -> rs.getString("audit_json") },
            employerId.value,
            paycheckId,
        ).firstOrNull() ?: return null

        return objectMapper.readValue(json, PaycheckAudit::class.java)
    }
}
