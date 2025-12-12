package com.example.uspayroll.orchestrator.payrun.persistence

import com.example.uspayroll.orchestrator.payrun.model.PayRunItemStatus
import com.example.uspayroll.orchestrator.payrun.model.PayRunStatusCounts
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class PayRunItemRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    fun upsertQueuedItems(employerId: String, payRunId: String, employeeIds: List<String>) {
        // Do not overwrite existing rows (especially paycheck_id) on retries.
        employeeIds.distinct().forEach { employeeId ->
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO pay_run_item (
                      employer_id, pay_run_id, employee_id,
                      status, paycheck_id,
                      attempt_count, last_error,
                      started_at, completed_at, updated_at
                    ) VALUES (?, ?, ?, ?, NULL, 0, NULL, NULL, NULL, CURRENT_TIMESTAMP)
                    """.trimIndent(),
                    employerId,
                    payRunId,
                    employeeId,
                    PayRunItemStatus.QUEUED.name,
                )
            } catch (e: DataIntegrityViolationException) {
                // already exists
            }
        }
    }

    /**
     * Returns an already-assigned paycheck_id for the item, or assigns a new one.
     */
    fun getOrAssignPaycheckId(employerId: String, payRunId: String, employeeId: String): String {
        val existing = jdbcTemplate.query(
            """
            SELECT paycheck_id
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
            """.trimIndent(),
            { rs, _ -> rs.getString("paycheck_id") },
            employerId,
            payRunId,
            employeeId,
        ).firstOrNull()

        if (!existing.isNullOrBlank()) return existing

        val newId = "chk-${UUID.randomUUID()}"
        val updated = jdbcTemplate.update(
            """
            UPDATE pay_run_item
            SET paycheck_id = ?, updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
              AND paycheck_id IS NULL
            """.trimIndent(),
            newId,
            employerId,
            payRunId,
            employeeId,
        )

        if (updated == 1) return newId

        return jdbcTemplate.query(
            """
            SELECT paycheck_id
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
            """.trimIndent(),
            { rs, _ -> rs.getString("paycheck_id") },
            employerId,
            payRunId,
            employeeId,
        ).firstOrNull() ?: newId
    }

    /**
     * Atomically claims up to [batchSize] QUEUED items by selecting them with
     * row locks and updating their status within a single transaction.
     */
    @Transactional
    fun claimQueuedItems(employerId: String, payRunId: String, batchSize: Int): List<String> {
        if (batchSize <= 0) return emptyList()

        // Row-lock the candidate set. This makes concurrent claimers block
        // rather than double-claiming the same employees.
        val ids = jdbcTemplate.query(
            """
            SELECT employee_id
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND status = ?
            ORDER BY employee_id
            LIMIT ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.getString("employee_id") },
            employerId,
            payRunId,
            PayRunItemStatus.QUEUED.name,
            batchSize,
        )

        if (ids.isEmpty()) return emptyList()

        val placeholders = ids.joinToString(",") { "?" }
        val args: MutableList<Any> = mutableListOf(
            PayRunItemStatus.RUNNING.name,
            employerId,
            payRunId,
            PayRunItemStatus.QUEUED.name,
        )
        args.addAll(ids)

        val updatedRows = jdbcTemplate.update(
            """
            UPDATE pay_run_item
            SET status = ?,
                attempt_count = attempt_count + 1,
                started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ?
              AND pay_run_id = ?
              AND status = ?
              AND employee_id IN ($placeholders)
            """.trimIndent(),
            *args.toTypedArray(),
        )

        // In a properly locked transaction this should be exact; if not, only
        // return the subset that are now RUNNING.
        return if (updatedRows == ids.size) {
            ids
        } else {
            jdbcTemplate.query(
                """
                SELECT employee_id
                FROM pay_run_item
                WHERE employer_id = ? AND pay_run_id = ? AND status = ?
                  AND employee_id IN ($placeholders)
                """.trimIndent(),
                { rs, _ -> rs.getString("employee_id") },
                employerId,
                payRunId,
                PayRunItemStatus.RUNNING.name,
                *ids.toTypedArray(),
            )
        }
    }

    fun markSucceeded(employerId: String, payRunId: String, employeeId: String, paycheckId: String) {
        jdbcTemplate.update(
            """
            UPDATE pay_run_item
            SET status = ?,
                paycheck_id = ?,
                last_error = NULL,
                completed_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
            """.trimIndent(),
            PayRunItemStatus.SUCCEEDED.name,
            paycheckId,
            employerId,
            payRunId,
            employeeId,
        )
    }

    fun markFailed(employerId: String, payRunId: String, employeeId: String, error: String) {
        val truncated = if (error.length <= 2000) error else error.take(2000)

        jdbcTemplate.update(
            """
            UPDATE pay_run_item
            SET status = ?,
                last_error = ?,
                completed_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
            """.trimIndent(),
            PayRunItemStatus.FAILED.name,
            truncated,
            employerId,
            payRunId,
            employeeId,
        )
    }

    /**
     * Requeues items that appear stuck in RUNNING (e.g. worker crash mid-item).
     *
     * We treat an item as stale when its [updated_at] timestamp is older than
     * [cutoff]. This is conservative: normal execution should keep updating
     * rows as they transition to SUCCEEDED/FAILED.
     */
    fun requeueStaleRunningItems(employerId: String, payRunId: String, cutoff: Instant, reason: String = "requeued_stale_running"): Int {
        val cutoffTs = Timestamp.from(cutoff)
        val msg = if (reason.length <= 2000) reason else reason.take(2000)

        return jdbcTemplate.update(
            """
            UPDATE pay_run_item
            SET status = ?,
                last_error = CASE
                    WHEN last_error IS NULL THEN ?
                    ELSE CONCAT(last_error, '; ', ?)
                END,
                started_at = NULL,
                completed_at = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ?
              AND pay_run_id = ?
              AND status = ?
              AND completed_at IS NULL
              AND updated_at < ?
            """.trimIndent(),
            PayRunItemStatus.QUEUED.name,
            msg,
            msg,
            employerId,
            payRunId,
            PayRunItemStatus.RUNNING.name,
            cutoffTs,
        )
    }

    fun countsForPayRun(employerId: String, payRunId: String): PayRunStatusCounts {
        val row = jdbcTemplate.query(
            """
            SELECT
              COUNT(*) AS total,
              SUM(CASE WHEN status = ? THEN 1 ELSE 0 END) AS queued,
              SUM(CASE WHEN status = ? THEN 1 ELSE 0 END) AS running,
              SUM(CASE WHEN status = ? THEN 1 ELSE 0 END) AS succeeded,
              SUM(CASE WHEN status = ? THEN 1 ELSE 0 END) AS failed
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ?
            """.trimIndent(),
            { rs, _ ->
                PayRunStatusCounts(
                    total = rs.getInt("total"),
                    queued = rs.getInt("queued"),
                    running = rs.getInt("running"),
                    succeeded = rs.getInt("succeeded"),
                    failed = rs.getInt("failed"),
                )
            },
            PayRunItemStatus.QUEUED.name,
            PayRunItemStatus.RUNNING.name,
            PayRunItemStatus.SUCCEEDED.name,
            PayRunItemStatus.FAILED.name,
            employerId,
            payRunId,
        ).firstOrNull()

        return row ?: PayRunStatusCounts(total = 0, queued = 0, running = 0, succeeded = 0, failed = 0)
    }

    fun hasAnyQueuedOrRunning(employerId: String, payRunId: String): Boolean {
        val n = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND status IN (?, ?)
            """.trimIndent(),
            Long::class.java,
            employerId,
            payRunId,
            PayRunItemStatus.QUEUED.name,
            PayRunItemStatus.RUNNING.name,
        )
        return (n ?: 0L) > 0L
    }

    fun listFailedItems(employerId: String, payRunId: String, limit: Int = 50): List<Pair<String, String?>> {
        val effectiveLimit = limit.coerceIn(1, 500)
        return jdbcTemplate.query(
            """
            SELECT employee_id, last_error
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND status = ?
            ORDER BY employee_id
            LIMIT ?
            """.trimIndent(),
            { rs, _ -> rs.getString("employee_id") to rs.getString("last_error") },
            employerId,
            payRunId,
            PayRunItemStatus.FAILED.name,
            effectiveLimit,
        )
    }

    fun listSucceededPaychecks(employerId: String, payRunId: String, limit: Int = 10_000): List<Pair<String, String>> {
        val effectiveLimit = limit.coerceIn(1, 100_000)
        return jdbcTemplate.query(
            """
            SELECT employee_id, paycheck_id
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND status = ? AND paycheck_id IS NOT NULL
            ORDER BY employee_id
            LIMIT ?
            """.trimIndent(),
            { rs, _ -> rs.getString("employee_id") to rs.getString("paycheck_id") },
            employerId,
            payRunId,
            PayRunItemStatus.SUCCEEDED.name,
            effectiveLimit,
        )
    }
}
