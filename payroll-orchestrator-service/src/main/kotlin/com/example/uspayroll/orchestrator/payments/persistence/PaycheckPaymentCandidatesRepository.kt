package com.example.uspayroll.orchestrator.payments.persistence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class PaycheckPaymentCandidatesRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    data class Candidate(
        val employeeId: String,
        val paycheckId: String,
        val payPeriodId: String,
        val currency: String,
        val netCents: Long,
    )

    fun listSucceededCandidates(employerId: String, payRunId: String): List<Candidate> =
        jdbcTemplate.query(
            """
            SELECT pri.employee_id, pri.paycheck_id, p.pay_period_id, p.currency, p.net_cents
            FROM pay_run_item pri
            JOIN paycheck p
              ON p.employer_id = pri.employer_id
             AND p.paycheck_id = pri.paycheck_id
            WHERE pri.employer_id = ?
              AND pri.pay_run_id = ?
              AND pri.status = 'SUCCEEDED'
            ORDER BY pri.employee_id
            """.trimIndent(),
            { rs, _ ->
                Candidate(
                    employeeId = rs.getString("employee_id"),
                    paycheckId = rs.getString("paycheck_id"),
                    payPeriodId = rs.getString("pay_period_id"),
                    currency = rs.getString("currency"),
                    netCents = rs.getLong("net_cents"),
                )
            },
            employerId,
            payRunId,
        )
}
