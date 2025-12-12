package com.example.uspayroll.payments.persistence

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class PaymentBatchStatus {
    CREATED,
    PROCESSING,
    COMPLETED,
    PARTIALLY_COMPLETED,
    FAILED,
}

data class PaymentBatchRow(
    val employerId: String,
    val batchId: String,
    val payRunId: String,
    val status: PaymentBatchStatus,
    val totalPayments: Int,
    val settledPayments: Int,
    val failedPayments: Int,
    val attempts: Int,
    val nextAttemptAt: Instant?,
    val lastError: String?,
    val lockedBy: String?,
    val lockedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Repository
class PaymentBatchRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findByBatchId(employerId: String, batchId: String): PaymentBatchRow? = jdbcTemplate.query(
        """
            SELECT employer_id, batch_id, pay_run_id, status,
                   total_payments, settled_payments, failed_payments,
                   attempts, next_attempt_at, last_error,
                   locked_by, locked_at,
                   created_at, updated_at
            FROM payment_batch
            WHERE employer_id = ? AND batch_id = ?
        """.trimIndent(),
        { rs, _ ->
            val lockedAtTs = rs.getTimestamp("locked_at")
            val nextAttemptTs = rs.getTimestamp("next_attempt_at")
            val createdAtTs = rs.getTimestamp("created_at")
            val updatedAtTs = rs.getTimestamp("updated_at")

            PaymentBatchRow(
                employerId = rs.getString("employer_id"),
                batchId = rs.getString("batch_id"),
                payRunId = rs.getString("pay_run_id"),
                status = PaymentBatchStatus.valueOf(rs.getString("status")),
                totalPayments = rs.getInt("total_payments"),
                settledPayments = rs.getInt("settled_payments"),
                failedPayments = rs.getInt("failed_payments"),
                attempts = rs.getInt("attempts"),
                nextAttemptAt = nextAttemptTs?.toInstant(),
                lastError = rs.getString("last_error"),
                lockedBy = rs.getString("locked_by"),
                lockedAt = lockedAtTs?.toInstant(),
                createdAt = createdAtTs.toInstant(),
                updatedAt = updatedAtTs.toInstant(),
            )
        },
        employerId,
        batchId,
    ).firstOrNull()

    fun findBatchIdForPayRun(employerId: String, payRunId: String): String? = jdbcTemplate.query(
        """
            SELECT batch_id
            FROM pay_run_payment_batch
            WHERE employer_id = ? AND pay_run_id = ?
        """.trimIndent(),
        { rs, _ -> rs.getString("batch_id") },
        employerId,
        payRunId,
    ).firstOrNull()

    /**
     * Creates a new batch and mapping for a payrun if one does not already exist.
     */
    @Transactional
    fun getOrCreateBatchForPayRun(employerId: String, payRunId: String, now: Instant = Instant.now()): String {
        val existing = findBatchIdForPayRun(employerId, payRunId)
        if (existing != null) return existing

        val batchId = "pbat-${UUID.randomUUID()}"
        val ts = Timestamp.from(now)

        try {
            jdbcTemplate.update(
                """
                INSERT INTO payment_batch (
                  employer_id, batch_id, pay_run_id,
                  status,
                  total_payments, settled_payments, failed_payments,
                  attempts, next_attempt_at, last_error,
                  locked_by, locked_at,
                  created_at, updated_at
                ) VALUES (?, ?, ?, ?, 0, 0, 0, 0, NULL, NULL, NULL, NULL, ?, ?)
                """.trimIndent(),
                employerId,
                batchId,
                payRunId,
                PaymentBatchStatus.CREATED.name,
                ts,
                ts,
            )

            jdbcTemplate.update(
                """
                INSERT INTO pay_run_payment_batch (employer_id, pay_run_id, batch_id, created_at)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                employerId,
                payRunId,
                batchId,
                ts,
            )

            return batchId
        } catch (_: DataIntegrityViolationException) {
            // Concurrent creation: read existing mapping.
            return findBatchIdForPayRun(employerId, payRunId)
                ?: throw IllegalStateException("batch creation race but mapping not found")
        }
    }

    data class BatchCounts(
        val total: Int,
        val settled: Int,
        val failed: Int,
    )

    fun computeCountsForBatch(employerId: String, batchId: String): BatchCounts {
        return jdbcTemplate.query(
            """
            SELECT
              COUNT(*) AS total,
              SUM(CASE WHEN status = 'SETTLED' THEN 1 ELSE 0 END) AS settled,
              SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed
            FROM paycheck_payment
            WHERE employer_id = ? AND batch_id = ?
            """.trimIndent(),
            { rs, _ ->
                BatchCounts(
                    total = rs.getInt("total"),
                    settled = rs.getInt("settled"),
                    failed = rs.getInt("failed"),
                )
            },
            employerId,
            batchId,
        ).firstOrNull() ?: BatchCounts(0, 0, 0)
    }

    fun reconcileBatch(employerId: String, batchId: String): PaymentBatchRow? {
        val batch = findByBatchId(employerId, batchId) ?: return null
        val counts = computeCountsForBatch(employerId, batchId)

        val derivedStatus = when {
            counts.total == 0 -> PaymentBatchStatus.CREATED
            counts.settled == counts.total -> PaymentBatchStatus.COMPLETED
            counts.failed == counts.total -> PaymentBatchStatus.FAILED
            counts.settled > 0 && counts.failed > 0 -> PaymentBatchStatus.PARTIALLY_COMPLETED
            else -> PaymentBatchStatus.PROCESSING
        }

        jdbcTemplate.update(
            """
            UPDATE payment_batch
            SET status = ?,
                total_payments = ?,
                settled_payments = ?,
                failed_payments = ?,
                locked_by = NULL,
                locked_at = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND batch_id = ?
            """.trimIndent(),
            derivedStatus.name,
            counts.total,
            counts.settled,
            counts.failed,
            employerId,
            batchId,
        )

        return findByBatchId(employerId, batchId)
    }

    /**
     * Claims batches to process.
     */
    @Transactional
    fun claimActiveBatches(limit: Int, lockOwner: String, lockTtl: Duration, now: Instant = Instant.now()): List<PaymentBatchRow> {
        val effectiveLimit = limit.coerceIn(1, 100)
        val nowTs = Timestamp.from(now)
        val cutoffTs = Timestamp.from(now.minus(lockTtl))

        val rows = jdbcTemplate.query(
            """
            SELECT employer_id, batch_id, pay_run_id, status,
                   total_payments, settled_payments, failed_payments,
                   attempts, next_attempt_at, last_error,
                   locked_by, locked_at,
                   created_at, updated_at
            FROM payment_batch
            WHERE status IN ('CREATED','PROCESSING','PARTIALLY_COMPLETED')
              AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
              AND (locked_at IS NULL OR locked_at < ?)
            ORDER BY created_at
            LIMIT ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                val lockedAtTs = rs.getTimestamp("locked_at")
                val nextAttemptTs = rs.getTimestamp("next_attempt_at")
                val createdAtTs = rs.getTimestamp("created_at")
                val updatedAtTs = rs.getTimestamp("updated_at")

                PaymentBatchRow(
                    employerId = rs.getString("employer_id"),
                    batchId = rs.getString("batch_id"),
                    payRunId = rs.getString("pay_run_id"),
                    status = PaymentBatchStatus.valueOf(rs.getString("status")),
                    totalPayments = rs.getInt("total_payments"),
                    settledPayments = rs.getInt("settled_payments"),
                    failedPayments = rs.getInt("failed_payments"),
                    attempts = rs.getInt("attempts"),
                    nextAttemptAt = nextAttemptTs?.toInstant(),
                    lastError = rs.getString("last_error"),
                    lockedBy = rs.getString("locked_by"),
                    lockedAt = lockedAtTs?.toInstant(),
                    createdAt = createdAtTs.toInstant(),
                    updatedAt = updatedAtTs.toInstant(),
                )
            },
            nowTs,
            cutoffTs,
            effectiveLimit,
        )

        if (rows.isEmpty()) return emptyList()

        jdbcTemplate.batchUpdate(
            """
            UPDATE payment_batch
            SET status = ?, locked_by = ?, locked_at = ?, updated_at = ?
            WHERE employer_id = ? AND batch_id = ?
            """.trimIndent(),
            rows.map {
                arrayOf(
                    PaymentBatchStatus.PROCESSING.name,
                    lockOwner,
                    nowTs,
                    nowTs,
                    it.employerId,
                    it.batchId,
                )
            },
        )

        return rows
    }
}
