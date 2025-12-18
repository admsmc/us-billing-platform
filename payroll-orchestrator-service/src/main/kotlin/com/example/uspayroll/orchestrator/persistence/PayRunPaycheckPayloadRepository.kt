package com.example.uspayroll.orchestrator.persistence

import com.example.uspayroll.payroll.model.PaycheckResult
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class PayRunPaycheckPayloadRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {

    data class Row(
        val employeeId: String,
        val paycheckId: String,
        val payload: PaycheckResult,
    )

    fun listSucceededPaychecks(employerId: String, payRunId: String, limit: Int = 10_000): List<Row> {
        val lim = limit.coerceIn(1, 50_000)

        return jdbcTemplate.query(
            """
            SELECT pri.employee_id, pri.paycheck_id, p.payload_json
            FROM pay_run_item pri
            JOIN paycheck p
              ON p.employer_id = pri.employer_id
             AND p.paycheck_id = pri.paycheck_id
            WHERE pri.employer_id = ?
              AND pri.pay_run_id = ?
              AND pri.status = 'SUCCEEDED'
            ORDER BY pri.employee_id
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                val employeeId = rs.getString("employee_id")
                val paycheckId = rs.getString("paycheck_id")
                val json = rs.getString("payload_json")
                val payload = objectMapper.readValue(json, PaycheckResult::class.java)
                Row(
                    employeeId = employeeId,
                    paycheckId = paycheckId,
                    payload = payload,
                )
            },
            employerId,
            payRunId,
            lim,
        )
    }
}
