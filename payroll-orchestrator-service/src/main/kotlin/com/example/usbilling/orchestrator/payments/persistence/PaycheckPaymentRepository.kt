package com.example.usbilling.orchestrator.payments.persistence

import com.example.usbilling.orchestrator.payments.model.PaycheckPaymentRecord
import com.example.usbilling.orchestrator.payments.model.PaycheckPaymentStatus
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class PaycheckPaymentRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val isPostgres: Boolean by lazy {
        val ds = jdbcTemplate.dataSource ?: return@lazy false
        ds.connection.use { conn ->
            conn.metaData.databaseProductName.lowercase().contains("postgres")
        }
    }

    data class Candidate(
        val payRunId: String,
        val employeeId: String,
        val payPeriodId: String,
        val currency: String,
        val netCents: Long,
    )

    fun findCandidateByPaycheck(employerId: String, paycheckId: String): Candidate? = jdbcTemplate.query(
        """
            SELECT pri.pay_run_id, pri.employee_id, p.pay_period_id, p.currency, p.net_cents
            FROM pay_run_item pri
            JOIN paycheck p
              ON p.employer_id = pri.employer_id
             AND p.paycheck_id = pri.paycheck_id
            WHERE pri.employer_id = ? AND pri.paycheck_id = ?
        """.trimIndent(),
        { rs, _ ->
            Candidate(
                payRunId = rs.getString("pay_run_id"),
                employeeId = rs.getString("employee_id"),
                payPeriodId = rs.getString("pay_period_id"),
                currency = rs.getString("currency"),
                netCents = rs.getLong("net_cents"),
            )
        },
        employerId,
        paycheckId,
    ).firstOrNull()

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
        // Postgres: use ON CONFLICT to avoid transaction-aborting constraint violations.
        if (isPostgres) {
            val inserted = jdbcTemplate.update(
                """
                    INSERT INTO paycheck_payment (
                      employer_id, payment_id, paycheck_id,
                      pay_run_id, employee_id, pay_period_id,
                      currency, net_cents,
                      status,
                      created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT DO NOTHING
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
            return inserted == 1
        }

        // H2 (tests): fall back to exception-based idempotency.
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

    fun updateStatusByPaycheck(employerId: String, paycheckId: String, status: PaycheckPaymentStatus): Int = jdbcTemplate.update(
        """
            UPDATE paycheck_payment
            SET status = ?, updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND paycheck_id = ?
        """.trimIndent(),
        status.name,
        employerId,
        paycheckId,
    )

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
