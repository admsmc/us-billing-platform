package com.example.usbilling.payments.sweeper

import com.example.usbilling.payments.events.PaymentBatchEventPublisher
import com.example.usbilling.payments.persistence.PaymentBatchRepository
import com.example.usbilling.payments.persistence.PaymentBatchStatus
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

@ConfigurationProperties(prefix = "payments.batch-sweeper")
data class PaymentBatchSweeperProperties(
    var enabled: Boolean = false,
    var fixedDelayMillis: Long = 5_000L,
    var sweepLimit: Int = 200,
    var lockTtlSeconds: Long = 60,
    var maxBatchAttempts: Int = 5,
    var retryBaseMillis: Long = 30_000L,
    var retryMaxMillis: Long = 15 * 60 * 1000L,
    var maxPaymentAttempts: Int = 3,
)

@Configuration
@EnableScheduling
@EnableConfigurationProperties(PaymentBatchSweeperProperties::class)
class PaymentBatchSweeperConfig

@Component
@ConditionalOnProperty(prefix = "payments.batch-sweeper", name = ["enabled"], havingValue = "true")
class PaymentBatchSweeper(
    private val props: PaymentBatchSweeperProperties,
    private val jdbcTemplate: JdbcTemplate,
    private val batches: PaymentBatchRepository,
    private val publisher: PaymentBatchEventPublisher,
) {
    private val logger = LoggerFactory.getLogger(PaymentBatchSweeper::class.java)

    @Scheduled(fixedDelayString = "\${payments.batch-sweeper.fixed-delay-millis:5000}")
    fun tick() {
        tickOnce()
    }

    fun tickOnce(now: Instant = Instant.now()): Int {
        val lockTtl = Duration.ofSeconds(props.lockTtlSeconds.coerceAtLeast(5L))
        val cutoffTs = Timestamp.from(now.minus(lockTtl))

        val candidates = jdbcTemplate.query(
            """
            SELECT employer_id, batch_id
            FROM payment_batch
            WHERE status IN ('PROCESSING','PARTIALLY_COMPLETED')
              AND (locked_at IS NULL OR locked_at < ?)
            ORDER BY updated_at
            LIMIT ?
            """.trimIndent(),
            { rs, _ -> rs.getString("employer_id") to rs.getString("batch_id") },
            cutoffTs,
            props.sweepLimit.coerceIn(1, 1000),
        )

        if (candidates.isEmpty()) return 0

        var touched = 0

        candidates.forEach { (employerId, batchId) ->
            // Recompute counters/status from payment rows.
            val reconciled = batches.reconcileBatch(employerId, batchId) ?: return@forEach
            publisher.publishBatchStatusChanged(reconciled, now = now)
            touched += 1

            if (reconciled.status != PaymentBatchStatus.PARTIALLY_COMPLETED) {
                return@forEach
            }

            // If out of attempts, mark FAILED and emit.
            val attempts = reconciled.attempts

            if (attempts >= props.maxBatchAttempts) {
                jdbcTemplate.update(
                    """
                    UPDATE payment_batch
                    SET status = 'FAILED', next_attempt_at = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE employer_id = ? AND batch_id = ?
                    """.trimIndent(),
                    employerId,
                    batchId,
                )
                val afterFail = batches.findByBatchId(employerId, batchId) ?: return@forEach
                publisher.publishBatchStatusChanged(afterFail, now = now)
                return@forEach
            }

            val nextAttemptAt = reconciled.nextAttemptAt

            // If no retry scheduled yet, schedule one with backoff.
            if (nextAttemptAt == null) {
                val delay = computeBackoff(attempts, props.retryBaseMillis, props.retryMaxMillis)
                val next = Timestamp.from(now.plusMillis(delay))
                jdbcTemplate.update(
                    """
                    UPDATE payment_batch
                    SET next_attempt_at = ?, attempts = attempts + 1, updated_at = CURRENT_TIMESTAMP
                    WHERE employer_id = ? AND batch_id = ?
                    """.trimIndent(),
                    next,
                    employerId,
                    batchId,
                )
                val afterSchedule = batches.findByBatchId(employerId, batchId) ?: return@forEach
                publisher.publishBatchStatusChanged(afterSchedule, now = now)
                return@forEach
            }

            // If retry is due, reopen failed payments and re-open the batch.
            if (nextAttemptAt.isAfter(now)) {
                return@forEach
            }

            val reopened = reopenFailedPaymentsForRetry(employerId, batchId)
            if (reopened > 0) {
                jdbcTemplate.update(
                    """
                    UPDATE payment_batch
                    SET status = 'PROCESSING', next_attempt_at = NULL, locked_by = NULL, locked_at = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE employer_id = ? AND batch_id = ?
                    """.trimIndent(),
                    employerId,
                    batchId,
                )
                val afterReopen = batches.reconcileBatch(employerId, batchId) ?: return@forEach
                publisher.publishBatchStatusChanged(afterReopen, now = now)
            }
        }

        logger.info("payments.batch_sweeper.touched_batches count={}", touched)
        return touched
    }

    private fun reopenFailedPaymentsForRetry(employerId: String, batchId: String): Int {
        // Re-open FAILED payments that have remaining retry budget.
        return jdbcTemplate.update(
            """
            UPDATE paycheck_payment
            SET status = 'CREATED',
                next_attempt_at = NULL,
                last_error = NULL,
                locked_by = NULL,
                locked_at = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ?
              AND batch_id = ?
              AND status = 'FAILED'
              AND attempts < ?
            """.trimIndent(),
            employerId,
            batchId,
            props.maxPaymentAttempts,
        )
    }

    private fun computeBackoff(attempts: Int, baseMillis: Long, maxMillis: Long): Long {
        val exp = attempts.coerceIn(0, 20)
        val delay = baseMillis * (1L shl exp)
        return delay.coerceAtMost(maxMillis)
    }
}
