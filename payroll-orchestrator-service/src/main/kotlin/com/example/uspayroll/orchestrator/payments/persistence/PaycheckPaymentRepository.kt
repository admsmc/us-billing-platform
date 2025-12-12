package com.example.uspayroll.orchestrator.payments.persistence

import com.example.uspayroll.orchestrator.payments.model.PaycheckPaymentRecord
import com.example.uspayroll.orchestrator.payments.model.PaycheckPaymentStatus
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class PaycheckPaymentRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun insertIfAbsent(
        employerId: String,
        paymentId: String,
        paycheckId: String,
        payRunId: String,
        employeeId: String,
        payPeriodId: String,
        currency: String,
        netCents: Long,
        status: PaycheckPaymentStatus = PaycheckPaymentStatus.CREATED,
    ): Boolean {
        return try {
            val inserted = jdbcTemplate.update(
                """
                INSERT INTO paycheck_payment (
                  employer_id, payment_id, paycheck_id,
                  pay_run_id, employee_id, pay_period_id,
                  currency, net_cents,
                  status,
                  created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.trimIndent(),
                employerId,
                paymentId,
                paycheckId,
                payRunId,
                employeeId,
                payPeriodId,
                currency,
                netCents,
                status.name,
            )
            inserted == 1
        } catch (_: DataIntegrityViolationException) {
            false
        }
    }

    fun countByPayRun(employerId: String, payRunId: String): Long = jdbcTemplate.queryForObject(
        """
            SELECT COUNT(*)
            FROM paycheck_payment
            WHERE employer_id = ? AND pay_run_id = ?
        """.trimIndent(),
        Long::class.java,
        employerId,
        payRunId,
    ) ?: 0L

    fun listByPayRun(employerId: String, payRunId: String): List<PaycheckPaymentRecord> = jdbcTemplate.query(
        """
            SELECT employer_id, payment_id, paycheck_id, pay_run_id, employee_id, pay_period_id, currency, net_cents, status
            FROM paycheck_payment
            WHERE employer_id = ? AND pay_run_id = ?
            ORDER BY employee_id
        """.trimIndent(),
        { rs, _ ->
            PaycheckPaymentRecord(
                employerId = rs.getString("employer_id"),
                paymentId = rs.getString("payment_id"),
                paycheckId = rs.getString("paycheck_id"),
                payRunId = rs.getString("pay_run_id"),
                employeeId = rs.getString("employee_id"),
                payPeriodId = rs.getString("pay_period_id"),
                currency = rs.getString("currency"),
                netCents = rs.getLong("net_cents"),
                status = PaycheckPaymentStatus.valueOf(rs.getString("status")),
            )
        },
        employerId,
        payRunId,
    )
}
