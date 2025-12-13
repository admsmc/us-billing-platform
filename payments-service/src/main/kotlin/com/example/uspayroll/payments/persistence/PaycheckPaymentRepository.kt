package com.example.uspayroll.payments.persistence

import com.example.uspayroll.messaging.events.payments.PaycheckPaymentLifecycleStatus
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

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

    fun findByPaycheck(employerId: String, paycheckId: String): PaymentRow? = jdbcTemplate.query(
        """
            SELECT employer_id, payment_id, paycheck_id, pay_run_id, employee_id, pay_period_id, currency, net_cents, status, attempts, batch_id
            FROM paycheck_payment
            WHERE employer_id = ? AND paycheck_id = ?
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
        batchId: String?,
        now: Instant = Instant.now(),
    ): Boolean {
        // Postgres: use ON CONFLICT to avoid transaction-aborting constraint violations.
        if (isPostgres) {
            val inserted = jdbcTemplate.update(
                """
                INSERT INTO paycheck_payment (
                  employer_id, payment_id, paycheck_id,
                  pay_run_id, employee_id, pay_period_id,
                  currency, net_cents,
                  status, attempts,
                  next_attempt_at, last_error,
                  locked_by, locked_at,
                  batch_id,
                  created_at, submitted_at, settled_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NULL, NULL, NULL, NULL, ?, ?, NULL, NULL, ?)
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
                PaycheckPaymentLifecycleStatus.CREATED.name,
                batchId,
                Timestamp.from(now),
                Timestamp.from(now),
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
                  status, attempts,
                  next_attempt_at, last_error,
                  locked_by, locked_at,
                  batch_id,
                  created_at, submitted_at, settled_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NULL, NULL, NULL, NULL, ?, ?, NULL, NULL, ?)
                """.trimIndent(),
                employerId,
                paymentId,
                paycheckId,
                payRunId,
                employeeId,
                payPeriodId,
                currency,
                netCents,
                PaycheckPaymentLifecycleStatus.CREATED.name,
                batchId,
                Timestamp.from(now),
                Timestamp.from(now),
            )
            inserted == 1
        } catch (_: DataIntegrityViolationException) {
            false
        }
    }

    fun updateStatus(employerId: String, paymentId: String, status: PaycheckPaymentLifecycleStatus, error: String? = null, nextAttemptAt: Instant? = null, now: Instant = Instant.now()): Int {
        val truncated = error?.let { if (it.length <= 2000) it else it.take(2000) }
        val nowTs = Timestamp.from(now)
        val nextAttemptTs = nextAttemptAt?.let { Timestamp.from(it) }

        // Enforce a minimal state machine to avoid out-of-order updates overwriting terminal
        // statuses. Also make FAILED idempotent (do not increment attempts repeatedly).
        return jdbcTemplate.update(
            """
            UPDATE paycheck_payment
            SET status = ?,
                last_error = CASE
                    WHEN ? = 'FAILED' THEN ?
                    WHEN ? = 'SETTLED' THEN NULL
                    ELSE last_error
                END,
                next_attempt_at = CASE
                    WHEN ? = 'FAILED' THEN ?
                    ELSE NULL
                END,
                attempts = CASE
                    WHEN ? = 'FAILED' AND status <> 'FAILED' THEN attempts + 1
                    ELSE attempts
                END,
                locked_by = CASE WHEN ? IN ('SETTLED','FAILED') THEN NULL ELSE locked_by END,
                locked_at = CASE WHEN ? IN ('SETTLED','FAILED') THEN NULL ELSE locked_at END,
                submitted_at = CASE WHEN ? = 'SUBMITTED' THEN ? ELSE submitted_at END,
                settled_at = CASE WHEN ? = 'SETTLED' THEN ? ELSE settled_at END,
                updated_at = ?
            WHERE employer_id = ?
              AND payment_id = ?
              AND (
                status = ?
                OR (status = 'CREATED' AND ? = 'SUBMITTED')
                OR (status = 'SUBMITTED' AND ? IN ('SETTLED','FAILED'))
                OR (status = 'FAILED' AND ? = 'FAILED')
                OR (status = 'SETTLED' AND ? = 'SETTLED')
              )
            """.trimIndent(),
            // SET status = ?
            status.name,
            // last_error CASE
            status.name,
            truncated,
            status.name,
            // next_attempt_at CASE
            status.name,
            nextAttemptTs,
            // attempts CASE
            status.name,
            // locks
            status.name,
            status.name,
            // submitted_at / settled_at
            status.name,
            nowTs,
            status.name,
            nowTs,
            // updated_at
            nowTs,
            // WHERE keys
            employerId,
            paymentId,
            // allowed transitions
            status.name,
            status.name,
            status.name,
            status.name,
            status.name,
        )
    }

    /**
     * Claim CREATED payments for processing (CREATED -> SUBMITTED) in a single transaction.
     */
    @Transactional
    fun claimCreatedBatch(limit: Int, lockOwner: String, lockTtl: Duration, now: Instant = Instant.now()): List<PaymentRow> {
        val effectiveLimit = limit.coerceIn(1, 500)
        val nowTs = Timestamp.from(now)
        val cutoffTs = Timestamp.from(now.minus(lockTtl))

        val rows = jdbcTemplate.query(
            """
            SELECT employer_id, payment_id, paycheck_id, pay_run_id, employee_id, pay_period_id, currency, net_cents, status, attempts, batch_id
            FROM paycheck_payment
            WHERE status = 'CREATED'
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
            nowTs,
            cutoffTs,
            effectiveLimit,
        )

        if (rows.isEmpty()) return emptyList()

        // payment_id is only unique within (employer_id, payment_id), so update per-employer.
        rows.groupBy { it.employerId }.forEach { (empId, empRows) ->
            val ids = empRows.map { it.paymentId }
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
                ps.setString(6, empId)

                ids.forEachIndexed { idx, id ->
                    ps.setString(7 + idx, id)
                }
            }
        }

        return rows
    }

    fun listByPayRun(employerId: String, payRunId: String): List<PaymentRow> = jdbcTemplate.query(
        """
            SELECT employer_id, payment_id, paycheck_id, pay_run_id, employee_id, pay_period_id, currency, net_cents, status, attempts, batch_id
            FROM paycheck_payment
            WHERE employer_id = ? AND pay_run_id = ?
            ORDER BY employee_id
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
        payRunId,
    )
}
