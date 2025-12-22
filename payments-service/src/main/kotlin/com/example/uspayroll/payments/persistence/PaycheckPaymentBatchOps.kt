package com.example.uspayroll.payments.persistence

import com.example.uspayroll.messaging.events.payments.PaycheckPaymentLifecycleStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

@Repository
class PaycheckPaymentBatchOps(
    private val jdbcTemplate: JdbcTemplate,
) {
    data class PaymentRow(
        val employerId: String,
        val paymentId: String,
        val paycheckId: String,
        val payRunId: String,
        val employeeId: String,
        val payPeriodId: String,
        val currency: String,
        val netCents: Long,
        val status: PaycheckPaymentLifecycleStatus,
        val attempts: Int,
        val batchId: String?,
    )

    fun attachBatchIfMissing(employerId: String, paycheckId: String, batchId: String): Int = jdbcTemplate.update(
        """
            UPDATE paycheck_payment
            SET batch_id = COALESCE(batch_id, ?),
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND paycheck_id = ?
        """.trimIndent(),
        batchId,
        employerId,
        paycheckId,
    )

    @Transactional
    fun claimCreatedByBatch(employerId: String, batchId: String, limit: Int, lockOwner: String, lockTtl: Duration, now: Instant = Instant.now()): List<PaymentRow> {
        val effectiveLimit = limit.coerceIn(1, 500)
        val nowTs = Timestamp.from(now)
        val cutoffTs = Timestamp.from(now.minus(lockTtl))

        val rows = jdbcTemplate.query(
            """
            SELECT employer_id, payment_id, paycheck_id, pay_run_id, employee_id, pay_period_id, currency, net_cents, status, attempts, batch_id
            FROM paycheck_payment
            WHERE employer_id = ?
              AND batch_id = ?
              AND status = 'CREATED'
              AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
              AND (locked_at IS NULL OR locked_at < ?)
            ORDER BY created_at
            LIMIT ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                PaymentRow(
                    employerId = rs.getString("employer_id"),
                    paymentId = rs.getString("payment_id"),
                    paycheckId = rs.getString("paycheck_id"),
                    payRunId = rs.getString("pay_run_id"),
                    employeeId = rs.getString("employee_id"),
                    payPeriodId = rs.getString("pay_period_id"),
                    currency = rs.getString("currency"),
                    netCents = rs.getLong("net_cents"),
                    status = PaycheckPaymentLifecycleStatus.valueOf(rs.getString("status")),
                    attempts = rs.getInt("attempts"),
                    batchId = rs.getString("batch_id"),
                )
            },
            employerId,
            batchId,
            nowTs,
            cutoffTs,
            effectiveLimit,
        )

        if (rows.isEmpty()) return emptyList()

        val ids = rows.map { it.paymentId }
        val placeholders = ids.joinToString(",") { "?" }

        jdbcTemplate.update(
            """
            UPDATE paycheck_payment
            SET status = ?,
                locked_by = ?,
                locked_at = ?,
                submitted_at = ?,
                updated_at = ?
            WHERE employer_id = ?
              AND status = 'CREATED'
              AND payment_id IN ($placeholders)
            """.trimIndent(),
        ) { ps ->
            ps.setString(1, PaycheckPaymentLifecycleStatus.SUBMITTED.name)
            ps.setString(2, lockOwner)
            ps.setTimestamp(3, nowTs)
            ps.setTimestamp(4, nowTs)
            ps.setTimestamp(5, nowTs)
            ps.setString(6, employerId)

            ids.forEachIndexed { idx, id ->
                ps.setString(7 + idx, id)
            }
        }

        return rows
    }
}
